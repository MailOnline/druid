/*
 * Druid - a distributed column store.
 * Copyright 2012 - 2015 Metamarkets Group Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.druid.indexing.overlord;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.NamedType;
import com.google.common.base.Function;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import com.metamx.common.Granularity;
import com.metamx.common.ISE;
import com.metamx.common.guava.Comparators;
import com.metamx.emitter.EmittingLogger;
import com.metamx.emitter.core.Event;
import com.metamx.emitter.service.ServiceEmitter;
import com.metamx.emitter.service.ServiceEventBuilder;
import com.metamx.metrics.Monitor;
import com.metamx.metrics.MonitorScheduler;
import io.druid.client.FilteredServerView;
import io.druid.client.ServerView;
import io.druid.client.cache.MapCache;
import io.druid.data.input.Firehose;
import io.druid.data.input.FirehoseFactory;
import io.druid.data.input.InputRow;
import io.druid.data.input.MapBasedInputRow;
import io.druid.data.input.impl.InputRowParser;
import io.druid.granularity.QueryGranularity;
import io.druid.indexing.common.SegmentLoaderFactory;
import io.druid.indexing.common.TaskLock;
import io.druid.indexing.common.TaskStatus;
import io.druid.indexing.common.TaskToolbox;
import io.druid.indexing.common.TaskToolboxFactory;
import io.druid.indexing.common.TestUtils;
import io.druid.indexing.common.actions.LocalTaskActionClientFactory;
import io.druid.indexing.common.actions.LockListAction;
import io.druid.indexing.common.actions.SegmentInsertAction;
import io.druid.indexing.common.actions.TaskActionClientFactory;
import io.druid.indexing.common.actions.TaskActionToolbox;
import io.druid.indexing.common.config.TaskConfig;
import io.druid.indexing.common.config.TaskStorageConfig;
import io.druid.indexing.common.task.AbstractFixedIntervalTask;
import io.druid.indexing.common.task.IndexTask;
import io.druid.indexing.common.task.KillTask;
import io.druid.indexing.common.task.RealtimeIndexTask;
import io.druid.indexing.common.task.RealtimeIndexTaskTest;
import io.druid.indexing.common.task.Task;
import io.druid.indexing.common.task.TaskResource;
import io.druid.indexing.overlord.config.TaskQueueConfig;
import io.druid.jackson.DefaultObjectMapper;
import io.druid.metadata.IndexerSQLMetadataStorageCoordinator;
import io.druid.metadata.SQLMetadataStorageActionHandlerFactory;
import io.druid.metadata.TestDerbyConnector;
import io.druid.query.QueryRunnerFactoryConglomerate;
import io.druid.query.aggregation.AggregatorFactory;
import io.druid.query.aggregation.DoubleSumAggregatorFactory;
import io.druid.query.aggregation.LongSumAggregatorFactory;
import io.druid.segment.IndexIO;
import io.druid.segment.IndexMerger;
import io.druid.segment.IndexSpec;
import io.druid.segment.indexing.DataSchema;
import io.druid.segment.indexing.RealtimeIOConfig;
import io.druid.segment.indexing.RealtimeTuningConfig;
import io.druid.segment.indexing.granularity.UniformGranularitySpec;
import io.druid.segment.loading.DataSegmentArchiver;
import io.druid.segment.loading.DataSegmentMover;
import io.druid.segment.loading.DataSegmentPusher;
import io.druid.segment.loading.LocalDataSegmentKiller;
import io.druid.segment.loading.SegmentLoaderConfig;
import io.druid.segment.loading.SegmentLoaderLocalCacheManager;
import io.druid.segment.loading.SegmentLoadingException;
import io.druid.segment.loading.StorageLocationConfig;
import io.druid.segment.realtime.FireDepartment;
import io.druid.segment.realtime.FireDepartmentTest;
import io.druid.server.coordination.DataSegmentAnnouncer;
import io.druid.server.coordination.DruidServerMetadata;
import io.druid.timeline.DataSegment;
import io.druid.timeline.partition.NoneShardSpec;
import org.easymock.EasyMock;
import org.joda.time.DateTime;
import org.joda.time.Hours;
import org.joda.time.Interval;
import org.joda.time.Period;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

@RunWith(Parameterized.class)
public class TaskLifecycleTest
{
  private static final ObjectMapper MAPPER;
  private static final IndexMerger INDEX_MERGER;
  private static final IndexIO INDEX_IO;

  static {
    TestUtils testUtils = new TestUtils();
    MAPPER = testUtils.getTestObjectMapper();
    INDEX_MERGER = testUtils.getTestIndexMerger();
    INDEX_IO = testUtils.getTestIndexIO();
  }

  @Parameterized.Parameters(name = "taskStorageType={0}")
  public static Collection<String[]> constructFeed()
  {
    return Arrays.asList(new String[][]{{"HeapMemoryTaskStorage"}, {"MetadataTaskStorage"}});
  }

  public TaskLifecycleTest(String taskStorageType)
  {
    this.taskStorageType = taskStorageType;
  }

  public final
  @Rule
  TemporaryFolder temporaryFolder = new TemporaryFolder();

  private static final Ordering<DataSegment> byIntervalOrdering = new Ordering<DataSegment>()
  {
    @Override
    public int compare(DataSegment dataSegment, DataSegment dataSegment2)
    {
      return Comparators.intervalsByStartThenEnd().compare(dataSegment.getInterval(), dataSegment2.getInterval());
    }
  };
  private static DateTime now = new DateTime();
  private static final Iterable<InputRow> realtimeIdxTaskInputRows = ImmutableList.of(
      IR(now.toString("YYYY-MM-dd'T'HH:mm:ss"), "test_dim1", "test_dim2", 1.0f),
      IR(now.plus(new Period(Hours.ONE)).toString("YYYY-MM-dd'T'HH:mm:ss"), "test_dim1", "test_dim2", 2.0f),
      IR(now.plus(new Period(Hours.TWO)).toString("YYYY-MM-dd'T'HH:mm:ss"), "test_dim1", "test_dim2", 3.0f)
  );
  private static final Iterable<InputRow> IdxTaskInputRows = ImmutableList.of(
      IR("2010-01-01T01", "x", "y", 1),
      IR("2010-01-01T01", "x", "z", 1),
      IR("2010-01-02T01", "a", "b", 2),
      IR("2010-01-02T01", "a", "c", 1)
  );

  @Rule
  public final TestDerbyConnector.DerbyConnectorRule derbyConnectorRule = new TestDerbyConnector.DerbyConnectorRule();

  private final String taskStorageType;

  private ObjectMapper mapper;
  private TaskStorageQueryAdapter tsqa = null;
  private File tmpDir = null;
  private TaskStorage ts = null;
  private TaskLockbox tl = null;
  private TaskQueue tq = null;
  private TaskRunner tr = null;
  private MockIndexerMetadataStorageCoordinator mdc = null;
  private TaskActionClientFactory tac = null;
  private TaskToolboxFactory tb = null;
  private IndexSpec indexSpec;
  private QueryRunnerFactoryConglomerate queryRunnerFactoryConglomerate;
  private FilteredServerView serverView;
  private MonitorScheduler monitorScheduler;
  private ServiceEmitter emitter;
  private TaskQueueConfig tqc;
  private int pushedSegments;
  private int announcedSinks;
  private static CountDownLatch publishCountDown;
  private TestDerbyConnector testDerbyConnector;
  private List<ServerView.SegmentCallback> segmentCallbacks = new ArrayList<>();

  private static MockIndexerMetadataStorageCoordinator newMockMDC()
  {
    return new MockIndexerMetadataStorageCoordinator();
  }

  private static ServiceEmitter newMockEmitter()
  {
    return new ServiceEmitter(null, null, null)
    {
      @Override
      public void emit(Event event)
      {

      }

      @Override
      public void emit(ServiceEventBuilder builder)
      {

      }
    };
  }

  private static InputRow IR(String dt, String dim1, String dim2, float met)
  {
    return new MapBasedInputRow(
        new DateTime(dt).getMillis(),
        ImmutableList.of("dim1", "dim2"),
        ImmutableMap.<String, Object>of(
            "dim1", dim1,
            "dim2", dim2,
            "met", met
        )
    );
  }

  private static class MockExceptionalFirehoseFactory implements FirehoseFactory
  {
    @Override
    public Firehose connect(InputRowParser parser) throws IOException
    {
      return new Firehose()
      {
        @Override
        public boolean hasMore()
        {
          return true;
        }

        @Override
        public InputRow nextRow()
        {
          throw new RuntimeException("HA HA HA");
        }

        @Override
        public Runnable commit()
        {
          return new Runnable()
          {
            @Override
            public void run()
            {

            }
          };
        }

        @Override
        public void close() throws IOException
        {

        }
      };
    }
  }

  private static class MockFirehoseFactory implements FirehoseFactory
  {
    @JsonProperty
    private boolean usedByRealtimeIdxTask;

    @JsonCreator
    public MockFirehoseFactory(@JsonProperty("usedByRealtimeIdxTask") boolean usedByRealtimeIdxTask)
    {
      this.usedByRealtimeIdxTask = usedByRealtimeIdxTask;
    }

    @Override
    public Firehose connect(InputRowParser parser) throws IOException
    {
      final Iterator<InputRow> inputRowIterator = usedByRealtimeIdxTask
                                                  ? realtimeIdxTaskInputRows.iterator()
                                                  : IdxTaskInputRows.iterator();

      return new Firehose()
      {
        @Override
        public boolean hasMore()
        {
          return inputRowIterator.hasNext();
        }

        @Override
        public InputRow nextRow()
        {
          return inputRowIterator.next();
        }

        @Override
        public Runnable commit()
        {
          return new Runnable()
          {
            @Override
            public void run()
            {

            }
          };
        }

        @Override
        public void close() throws IOException
        {

        }
      };
    }
  }

  @Before
  public void setUp() throws Exception
  {
    emitter = EasyMock.createMock(ServiceEmitter.class);
    EmittingLogger.registerEmitter(emitter);
    queryRunnerFactoryConglomerate = EasyMock.createStrictMock(QueryRunnerFactoryConglomerate.class);
    monitorScheduler = EasyMock.createStrictMock(MonitorScheduler.class);
    publishCountDown = new CountDownLatch(1);
    announcedSinks = 0;
    pushedSegments = 0;
    tmpDir = temporaryFolder.newFolder();
    TestUtils testUtils = new TestUtils();
    mapper = testUtils.getTestObjectMapper();

    tqc = mapper.readValue(
        "{\"startDelay\":\"PT0S\", \"restartDelay\":\"PT1S\", \"storageSyncRate\":\"PT0.5S\"}",
        TaskQueueConfig.class
    );
    indexSpec = new IndexSpec();

    if (taskStorageType.equals("HeapMemoryTaskStorage")) {
      ts = new HeapMemoryTaskStorage(
          new TaskStorageConfig(null)
          {
          }
      );
    } else if (taskStorageType.equals("MetadataTaskStorage")) {
      testDerbyConnector = derbyConnectorRule.getConnector();
      mapper.registerSubtypes(
          new NamedType(MockExceptionalFirehoseFactory.class, "mockExcepFirehoseFactory"),
          new NamedType(MockFirehoseFactory.class, "mockFirehoseFactory")
      );
      testDerbyConnector.createTaskTables();
      testDerbyConnector.createSegmentTable();
      ts = new MetadataTaskStorage(
          testDerbyConnector,
          new TaskStorageConfig(null),
          new SQLMetadataStorageActionHandlerFactory(testDerbyConnector, derbyConnectorRule.metadataTablesConfigSupplier().get(), mapper)
      );
    } else {
      throw new RuntimeException(String.format("Unknown task storage type [%s]", taskStorageType));
    }

    serverView = new FilteredServerView()
    {
      @Override
      public void registerSegmentCallback(
          Executor exec, ServerView.SegmentCallback callback, Predicate<DataSegment> filter
      )
      {
        segmentCallbacks.add(callback);
      }
    };
    setUpAndStartTaskQueue(
        new DataSegmentPusher()
        {
          @Override
          public String getPathForHadoop(String dataSource)
          {
            throw new UnsupportedOperationException();
          }

          @Override
          public DataSegment push(File file, DataSegment segment) throws IOException
          {
            pushedSegments++;
            return segment;
          }
        }
    );
  }

  private void setUpAndStartTaskQueue(DataSegmentPusher dataSegmentPusher) {
    tsqa = new TaskStorageQueryAdapter(ts);
    tl = new TaskLockbox(ts);
    mdc = newMockMDC();
    tac = new LocalTaskActionClientFactory(ts, new TaskActionToolbox(tl, mdc, newMockEmitter()));
    tb = new TaskToolboxFactory(
        new TaskConfig(tmpDir.toString(), null, null, 50000, null),
        tac,
        newMockEmitter(),
        dataSegmentPusher,
        new LocalDataSegmentKiller(),
        new DataSegmentMover()
        {
          @Override
          public DataSegment move(DataSegment dataSegment, Map<String, Object> targetLoadSpec)
              throws SegmentLoadingException
          {
            return dataSegment;
          }
        },
        new DataSegmentArchiver()
        {
          @Override
          public DataSegment archive(DataSegment segment) throws SegmentLoadingException
          {
            return segment;
          }

          @Override
          public DataSegment restore(DataSegment segment) throws SegmentLoadingException
          {
            return segment;
          }
        },
        new DataSegmentAnnouncer()
        {
          @Override
          public void announceSegment(DataSegment segment) throws IOException
          {
            announcedSinks++;
          }

          @Override
          public void unannounceSegment(DataSegment segment) throws IOException
          {

          }

          @Override
          public void announceSegments(Iterable<DataSegment> segments) throws IOException
          {

          }

          @Override
          public void unannounceSegments(Iterable<DataSegment> segments) throws IOException
          {

          }
        }, // segment announcer
        serverView, // new segment server view
        queryRunnerFactoryConglomerate, // query runner factory conglomerate corporation unionized collective
        null, // query executor service
        monitorScheduler, // monitor scheduler
        new SegmentLoaderFactory(
            new SegmentLoaderLocalCacheManager(
                null,
                new SegmentLoaderConfig()
                {
                  @Override
                  public List<StorageLocationConfig> getLocations()
                  {
                    return Lists.newArrayList();
                  }
                }, new DefaultObjectMapper()
            )
        ),
        MAPPER,
        INDEX_MERGER,
        INDEX_IO,
        MapCache.create(0),
        FireDepartmentTest.NO_CACHE_CONFIG
    );
    tr = new ThreadPoolTaskRunner(tb, null);
    tq = new TaskQueue(tqc, ts, tr, tac, tl, emitter);
    tq.start();
  }

  @After
  public void tearDown()
  {
    tq.stop();
  }

  @Test
  public void testIndexTask() throws Exception
  {
    final Task indexTask = new IndexTask(
        null,
        null,
        new IndexTask.IndexIngestionSpec(
            new DataSchema(
                "foo",
                null,
                new AggregatorFactory[]{new DoubleSumAggregatorFactory("met", "met")},
                new UniformGranularitySpec(
                    Granularity.DAY,
                    null,
                    ImmutableList.of(new Interval("2010-01-01/P2D"))
                ),
                mapper
            ),
            new IndexTask.IndexIOConfig(new MockFirehoseFactory(false)),
            new IndexTask.IndexTuningConfig(10000, 10, -1, indexSpec)
        ),
        mapper,
        null
    );

    final Optional<TaskStatus> preRunTaskStatus = tsqa.getStatus(indexTask.getId());
    Assert.assertTrue("pre run task status not present", !preRunTaskStatus.isPresent());

    final TaskStatus mergedStatus = runTask(indexTask);
    final TaskStatus status = ts.getStatus(indexTask.getId()).get();
    final List<DataSegment> publishedSegments = byIntervalOrdering.sortedCopy(mdc.getPublished());
    final List<DataSegment> loggedSegments = byIntervalOrdering.sortedCopy(tsqa.getInsertedSegments(indexTask.getId()));

    Assert.assertEquals("statusCode", TaskStatus.Status.SUCCESS, status.getStatusCode());
    Assert.assertEquals("merged statusCode", TaskStatus.Status.SUCCESS, mergedStatus.getStatusCode());
    Assert.assertEquals("segments logged vs published", loggedSegments, publishedSegments);
    Assert.assertEquals("num segments published", 2, mdc.getPublished().size());
    Assert.assertEquals("num segments nuked", 0, mdc.getNuked().size());

    Assert.assertEquals("segment1 datasource", "foo", publishedSegments.get(0).getDataSource());
    Assert.assertEquals("segment1 interval", new Interval("2010-01-01/P1D"), publishedSegments.get(0).getInterval());
    Assert.assertEquals(
        "segment1 dimensions",
        ImmutableList.of("dim1", "dim2"),
        publishedSegments.get(0).getDimensions()
    );
    Assert.assertEquals("segment1 metrics", ImmutableList.of("met"), publishedSegments.get(0).getMetrics());

    Assert.assertEquals("segment2 datasource", "foo", publishedSegments.get(1).getDataSource());
    Assert.assertEquals("segment2 interval", new Interval("2010-01-02/P1D"), publishedSegments.get(1).getInterval());
    Assert.assertEquals(
        "segment2 dimensions",
        ImmutableList.of("dim1", "dim2"),
        publishedSegments.get(1).getDimensions()
    );
    Assert.assertEquals("segment2 metrics", ImmutableList.of("met"), publishedSegments.get(1).getMetrics());
  }

  @Test
  public void testIndexTaskFailure() throws Exception
  {
    final Task indexTask = new IndexTask(
        null,
        null,
        new IndexTask.IndexIngestionSpec(
            new DataSchema(
                "foo",
                null,
                new AggregatorFactory[]{new DoubleSumAggregatorFactory("met", "met")},
                new UniformGranularitySpec(
                    Granularity.DAY,
                    null,
                    ImmutableList.of(new Interval("2010-01-01/P1D"))
                ),
                mapper
            ),
            new IndexTask.IndexIOConfig(new MockExceptionalFirehoseFactory()),
            new IndexTask.IndexTuningConfig(10000, 10, -1, indexSpec)
        ),
        mapper,
        null
    );

    final TaskStatus status = runTask(indexTask);

    Assert.assertEquals("statusCode", TaskStatus.Status.FAILED, status.getStatusCode());
    Assert.assertEquals("num segments published", 0, mdc.getPublished().size());
    Assert.assertEquals("num segments nuked", 0, mdc.getNuked().size());
  }

  @Test
  public void testKillTask() throws Exception
  {
    final File tmpSegmentDir = temporaryFolder.newFolder();

    List<DataSegment> expectedUnusedSegments = Lists.transform(
        ImmutableList.<String>of(
            "2011-04-01/2011-04-02",
            "2011-04-02/2011-04-03",
            "2011-04-04/2011-04-05"
        ), new Function<String, DataSegment>()
        {
          @Override
          public DataSegment apply(String input)
          {
            final Interval interval = new Interval(input);
            try {
              return DataSegment.builder()
                                .dataSource("test_kill_task")
                                .interval(interval)
                                .loadSpec(
                                    ImmutableMap.<String, Object>of(
                                        "type",
                                        "local",
                                        "path",
                                        tmpSegmentDir.getCanonicalPath()
                                        + "/druid/localStorage/wikipedia/"
                                        + interval.getStart()
                                        + "-"
                                        + interval.getEnd()
                                        + "/"
                                        + "2011-04-6T16:52:46.119-05:00"
                                        + "/0/index.zip"
                                    )
                                )
                                .version("2011-04-6T16:52:46.119-05:00")
                                .dimensions(ImmutableList.<String>of())
                                .metrics(ImmutableList.<String>of())
                                .shardSpec(new NoneShardSpec())
                                .binaryVersion(9)
                                .size(0)
                                .build();
            }
            catch (IOException e) {
              throw new ISE(e, "Error creating segments");
            }
          }
        }
    );

    mdc.setUnusedSegments(expectedUnusedSegments);

    // manually create local segments files
    List<File> segmentFiles = Lists.newArrayList();
    for (DataSegment segment : mdc.getUnusedSegmentsForInterval("test_kill_task", new Interval("2011-04-01/P4D"))) {
      File file = new File((String) segment.getLoadSpec().get("path"));
      file.mkdirs();
      segmentFiles.add(file);
    }

    final Task killTask = new KillTask(null, "test_kill_task", new Interval("2011-04-01/P4D"), null);

    final TaskStatus status = runTask(killTask);
    Assert.assertEquals("merged statusCode", TaskStatus.Status.SUCCESS, status.getStatusCode());
    Assert.assertEquals("num segments published", 0, mdc.getPublished().size());
    Assert.assertEquals("num segments nuked", 3, mdc.getNuked().size());
    Assert.assertTrue(
        "expected unused segments get killed",
        expectedUnusedSegments.containsAll(mdc.getNuked()) && mdc.getNuked().containsAll(
            expectedUnusedSegments
        )
    );

    for (File file : segmentFiles) {
      Assert.assertFalse("unused segments files get deleted", file.exists());
    }
  }

  @Test
  public void testRealtimeishTask() throws Exception
  {
    final Task rtishTask = new RealtimeishTask();
    final TaskStatus status = runTask(rtishTask);

    Assert.assertEquals("statusCode", TaskStatus.Status.SUCCESS, status.getStatusCode());
    Assert.assertEquals("num segments published", 2, mdc.getPublished().size());
    Assert.assertEquals("num segments nuked", 0, mdc.getNuked().size());
  }

  @Test
  public void testNoopTask() throws Exception
  {
    final Task noopTask = new DefaultObjectMapper().readValue(
        "{\"type\":\"noop\", \"runTime\":\"100\"}\"",
        Task.class
    );
    final TaskStatus status = runTask(noopTask);

    Assert.assertEquals("statusCode", TaskStatus.Status.SUCCESS, status.getStatusCode());
    Assert.assertEquals("num segments published", 0, mdc.getPublished().size());
    Assert.assertEquals("num segments nuked", 0, mdc.getNuked().size());
  }

  @Test
  public void testNeverReadyTask() throws Exception
  {
    final Task neverReadyTask = new DefaultObjectMapper().readValue(
        "{\"type\":\"noop\", \"isReadyResult\":\"exception\"}\"",
        Task.class
    );
    final TaskStatus status = runTask(neverReadyTask);

    Assert.assertEquals("statusCode", TaskStatus.Status.FAILED, status.getStatusCode());
    Assert.assertEquals("num segments published", 0, mdc.getPublished().size());
    Assert.assertEquals("num segments nuked", 0, mdc.getNuked().size());
  }

  @Test
  public void testSimple() throws Exception
  {
    final Task task = new AbstractFixedIntervalTask(
        "id1",
        "id1",
        new TaskResource("id1", 1),
        "ds",
        new Interval("2012-01-01/P1D"),
        null
    )
    {
      @Override
      public String getType()
      {
        return "test";
      }

      @Override
      public TaskStatus run(TaskToolbox toolbox) throws Exception
      {
        final TaskLock myLock = Iterables.getOnlyElement(
            toolbox.getTaskActionClient()
                   .submit(new LockListAction())
        );

        final DataSegment segment = DataSegment.builder()
                                               .dataSource("ds")
                                               .interval(new Interval("2012-01-01/P1D"))
                                               .version(myLock.getVersion())
                                               .build();

        toolbox.getTaskActionClient().submit(new SegmentInsertAction(ImmutableSet.of(segment)));
        return TaskStatus.success(getId());
      }
    };

    final TaskStatus status = runTask(task);

    Assert.assertEquals("statusCode", TaskStatus.Status.SUCCESS, status.getStatusCode());
    Assert.assertEquals("segments published", 1, mdc.getPublished().size());
    Assert.assertEquals("segments nuked", 0, mdc.getNuked().size());
  }

  @Test
  public void testBadInterval() throws Exception
  {
    final Task task = new AbstractFixedIntervalTask("id1", "id1", "ds", new Interval("2012-01-01/P1D"), null)
    {
      @Override
      public String getType()
      {
        return "test";
      }

      @Override
      public TaskStatus run(TaskToolbox toolbox) throws Exception
      {
        final TaskLock myLock = Iterables.getOnlyElement(toolbox.getTaskActionClient().submit(new LockListAction()));

        final DataSegment segment = DataSegment.builder()
                                               .dataSource("ds")
                                               .interval(new Interval("2012-01-01/P2D"))
                                               .version(myLock.getVersion())
                                               .build();

        toolbox.getTaskActionClient().submit(new SegmentInsertAction(ImmutableSet.of(segment)));
        return TaskStatus.success(getId());
      }
    };

    final TaskStatus status = runTask(task);

    Assert.assertEquals("statusCode", TaskStatus.Status.FAILED, status.getStatusCode());
    Assert.assertEquals("segments published", 0, mdc.getPublished().size());
    Assert.assertEquals("segments nuked", 0, mdc.getNuked().size());
  }

  @Test
  public void testBadVersion() throws Exception
  {
    final Task task = new AbstractFixedIntervalTask("id1", "id1", "ds", new Interval("2012-01-01/P1D"), null)
    {
      @Override
      public String getType()
      {
        return "test";
      }

      @Override
      public TaskStatus run(TaskToolbox toolbox) throws Exception
      {
        final TaskLock myLock = Iterables.getOnlyElement(toolbox.getTaskActionClient().submit(new LockListAction()));

        final DataSegment segment = DataSegment.builder()
                                               .dataSource("ds")
                                               .interval(new Interval("2012-01-01/P1D"))
                                               .version(myLock.getVersion() + "1!!!1!!")
                                               .build();

        toolbox.getTaskActionClient().submit(new SegmentInsertAction(ImmutableSet.of(segment)));
        return TaskStatus.success(getId());
      }
    };

    final TaskStatus status = runTask(task);

    Assert.assertEquals("statusCode", TaskStatus.Status.FAILED, status.getStatusCode());
    Assert.assertEquals("segments published", 0, mdc.getPublished().size());
    Assert.assertEquals("segments nuked", 0, mdc.getNuked().size());
  }

  @Test (timeout = 4000L)
  public void testRealtimeIndexTask() throws Exception
  {
    monitorScheduler.addMonitor(EasyMock.anyObject(Monitor.class));
    EasyMock.expectLastCall().atLeastOnce();
    monitorScheduler.removeMonitor(EasyMock.anyObject(Monitor.class));
    EasyMock.expectLastCall().anyTimes();
    EasyMock.replay(monitorScheduler, queryRunnerFactoryConglomerate);

    RealtimeIndexTask realtimeIndexTask = giveMeARealtimeIndexTask();
    final String taskId = realtimeIndexTask.getId();

    tq.add(realtimeIndexTask);
    //wait for task to process events and publish segment
    Assert.assertTrue(publishCountDown.await(1000, TimeUnit.MILLISECONDS));

    // Realtime Task has published the segment, simulate loading of segment to a historical node so that task finishes with SUCCESS status
    segmentCallbacks.get(0).segmentAdded(
        new DruidServerMetadata(
            "dummy",
            "dummy_host",
            0,
            "historical",
            "dummy_tier",
            0
        ), mdc.getPublished().iterator().next()
    );

    // Wait for realtime index task to handle callback in plumber and succeed
    while (tsqa.getStatus(taskId).get().isRunnable()) {
      Thread.sleep(10);
    }

    Assert.assertTrue("Task should be in Success state", tsqa.getStatus(taskId).get().isSuccess());

    Assert.assertEquals(1, announcedSinks);
    Assert.assertEquals(1, pushedSegments);
    Assert.assertEquals(1, mdc.getPublished().size());
    DataSegment segment = mdc.getPublished().iterator().next();
    Assert.assertEquals("test_ds", segment.getDataSource());
    Assert.assertEquals(ImmutableList.of("dim1", "dim2"), segment.getDimensions());
    Assert.assertEquals(
        new Interval(now.toString("YYYY-MM-dd") + "/" + now.plusDays(1).toString("YYYY-MM-dd")),
        segment.getInterval()
    );
    Assert.assertEquals(ImmutableList.of("count"), segment.getMetrics());
    EasyMock.verify(monitorScheduler, queryRunnerFactoryConglomerate);
  }

  @Test (timeout = 4000L)
  public void testRealtimeIndexTaskFailure() throws Exception
  {
    setUpAndStartTaskQueue(
        new DataSegmentPusher()
        {
          @Override
          public String getPathForHadoop(String s)
          {
            throw new UnsupportedOperationException();
          }

          @Override
          public DataSegment push(File file, DataSegment dataSegment) throws IOException
          {
            throw new RuntimeException("FAILURE");
          }
        }
    );
    monitorScheduler.addMonitor(EasyMock.anyObject(Monitor.class));
    EasyMock.expectLastCall().atLeastOnce();
    monitorScheduler.removeMonitor(EasyMock.anyObject(Monitor.class));
    EasyMock.expectLastCall().anyTimes();
    EasyMock.replay(monitorScheduler, queryRunnerFactoryConglomerate);

    RealtimeIndexTask realtimeIndexTask = giveMeARealtimeIndexTask();
    final String taskId = realtimeIndexTask.getId();
    tq.add(realtimeIndexTask);

    // Wait for realtime index task to fail
    while (tsqa.getStatus(taskId).get().isRunnable()) {
      Thread.sleep(10);
    }

    Assert.assertTrue("Task should be in Failure state", tsqa.getStatus(taskId).get().isFailure());

    EasyMock.verify(monitorScheduler, queryRunnerFactoryConglomerate);
  }

  @Test
  public void testResumeTasks() throws Exception
  {
    final Task indexTask = new IndexTask(
        null,
        null,
        new IndexTask.IndexIngestionSpec(
            new DataSchema(
                "foo",
                null,
                new AggregatorFactory[]{new DoubleSumAggregatorFactory("met", "met")},
                new UniformGranularitySpec(
                    Granularity.DAY,
                    null,
                    ImmutableList.of(new Interval("2010-01-01/P2D"))
                ),
                mapper
            ),
            new IndexTask.IndexIOConfig(new MockFirehoseFactory(false)),
            new IndexTask.IndexTuningConfig(10000, 10, -1, indexSpec)
        ),
        mapper,
        null
    );

    final long startTime = System.currentTimeMillis();

    // manually insert the task into TaskStorage, waiting for TaskQueue to sync from storage
    ts.insert(indexTask, TaskStatus.running(indexTask.getId()));

    while (tsqa.getStatus(indexTask.getId()).get().isRunnable()) {
      if (System.currentTimeMillis() > startTime + 10 * 1000) {
        throw new ISE("Where did the task go?!: %s", indexTask.getId());
      }

      Thread.sleep(100);
    }

    final TaskStatus status = ts.getStatus(indexTask.getId()).get();
    final List<DataSegment> publishedSegments = byIntervalOrdering.sortedCopy(mdc.getPublished());
    final List<DataSegment> loggedSegments = byIntervalOrdering.sortedCopy(tsqa.getInsertedSegments(indexTask.getId()));

    Assert.assertEquals("statusCode", TaskStatus.Status.SUCCESS, status.getStatusCode());
    Assert.assertEquals("segments logged vs published", loggedSegments, publishedSegments);
    Assert.assertEquals("num segments published", 2, mdc.getPublished().size());
    Assert.assertEquals("num segments nuked", 0, mdc.getNuked().size());

    Assert.assertEquals("segment1 datasource", "foo", publishedSegments.get(0).getDataSource());
    Assert.assertEquals("segment1 interval", new Interval("2010-01-01/P1D"), publishedSegments.get(0).getInterval());
    Assert.assertEquals(
        "segment1 dimensions",
        ImmutableList.of("dim1", "dim2"),
        publishedSegments.get(0).getDimensions()
    );
    Assert.assertEquals("segment1 metrics", ImmutableList.of("met"), publishedSegments.get(0).getMetrics());

    Assert.assertEquals("segment2 datasource", "foo", publishedSegments.get(1).getDataSource());
    Assert.assertEquals("segment2 interval", new Interval("2010-01-02/P1D"), publishedSegments.get(1).getInterval());
    Assert.assertEquals(
        "segment2 dimensions",
        ImmutableList.of("dim1", "dim2"),
        publishedSegments.get(1).getDimensions()
    );
    Assert.assertEquals("segment2 metrics", ImmutableList.of("met"), publishedSegments.get(1).getMetrics());
  }

  private TaskStatus runTask(final Task task) throws Exception
  {
    final Task dummyTask = new DefaultObjectMapper().readValue(
        "{\"type\":\"noop\", \"isReadyResult\":\"exception\"}\"",
        Task.class
    );
    final long startTime = System.currentTimeMillis();

    Preconditions.checkArgument(!task.getId().equals(dummyTask.getId()));

    tq.add(dummyTask);
    tq.add(task);

    TaskStatus retVal = null;

    for (final String taskId : ImmutableList.of(dummyTask.getId(), task.getId())) {
      try {
        TaskStatus status;
        while ((status = tsqa.getStatus(taskId).get()).isRunnable()) {
          if (System.currentTimeMillis() > startTime + 10 * 1000) {
            throw new ISE("Where did the task go?!: %s", task.getId());
          }

          Thread.sleep(100);
        }
        if (taskId.equals(task.getId())) {
          retVal = status;
        }
      }
      catch (Exception e) {
        throw Throwables.propagate(e);
      }
    }

    return retVal;
  }

  private RealtimeIndexTask giveMeARealtimeIndexTask() {
    String taskId = String.format("rt_task_%s", System.currentTimeMillis());
    DataSchema dataSchema = new DataSchema(
        "test_ds",
        null,
        new AggregatorFactory[]{new LongSumAggregatorFactory("count", "rows")},
        new UniformGranularitySpec(Granularity.DAY, QueryGranularity.NONE, null),
        mapper
    );
    RealtimeIOConfig realtimeIOConfig = new RealtimeIOConfig(
        new MockFirehoseFactory(true),
        null, // PlumberSchool - Realtime Index Task always uses RealtimePlumber which is hardcoded in RealtimeIndexTask class
        null
    );
    RealtimeTuningConfig realtimeTuningConfig = new RealtimeTuningConfig(
        1000,
        new Period("P1Y"),
        null, //default window period of 10 minutes
        null, // base persist dir ignored by Realtime Index task
        null,
        null,
        null,
        null,
        null
    );
    FireDepartment fireDepartment = new FireDepartment(dataSchema, realtimeIOConfig, realtimeTuningConfig);
    return new RealtimeIndexTask(
        taskId,
        new TaskResource(taskId, 1),
        fireDepartment,
        null
    );
  }

  private static class MockIndexerMetadataStorageCoordinator extends IndexerSQLMetadataStorageCoordinator
  {
    final private Set<DataSegment> published = Sets.newHashSet();
    final private Set<DataSegment> nuked = Sets.newHashSet();

    private List<DataSegment> unusedSegments;

    private MockIndexerMetadataStorageCoordinator()
    {
      super(null, null, null);
      unusedSegments = Lists.newArrayList();
    }

    @Override
    public List<DataSegment> getUsedSegmentsForInterval(String dataSource, Interval interval) throws IOException
    {
      return ImmutableList.of();
    }

    @Override
    public List<DataSegment> getUnusedSegmentsForInterval(String dataSource, Interval interval)
    {
      return unusedSegments;
    }

    @Override
    public Set<DataSegment> announceHistoricalSegments(Set<DataSegment> segments)
    {
      Set<DataSegment> added = Sets.newHashSet();
      for (final DataSegment segment : segments) {
        if (published.add(segment)) {
          added.add(segment);
        }
      }
      TaskLifecycleTest.publishCountDown.countDown();
      return ImmutableSet.copyOf(added);
    }

    @Override
    public void deleteSegments(Set<DataSegment> segments)
    {
      nuked.addAll(segments);
    }

    public Set<DataSegment> getPublished()
    {
      return ImmutableSet.copyOf(published);
    }

    public Set<DataSegment> getNuked()
    {
      return ImmutableSet.copyOf(nuked);
    }

    public void setUnusedSegments(List<DataSegment> unusedSegments)
    {
      this.unusedSegments = unusedSegments;
    }
  }
}
