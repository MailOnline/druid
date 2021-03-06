/*
* Licensed to Metamarkets Group Inc. (Metamarkets) under one
* or more contributor license agreements. See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership. Metamarkets licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License. You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied. See the License for the
* specific language governing permissions and limitations
* under the License.
*/

package io.druid.query.aggregation.datasketches.theta;

import com.yahoo.sketches.Family;
import com.yahoo.sketches.memory.Memory;
import com.yahoo.sketches.theta.SetOpReturnState;
import com.yahoo.sketches.theta.SetOperation;
import com.yahoo.sketches.theta.Sketch;
import com.yahoo.sketches.theta.Union;
import io.druid.query.aggregation.Aggregator;
import io.druid.segment.ObjectColumnSelector;

public class SketchAggregator implements Aggregator
{
  private final ObjectColumnSelector selector;
  private final String name;
  private final int size;

  private Union union;

  public SketchAggregator(String name, ObjectColumnSelector selector, int size)
  {
    this.name = name;
    this.selector = selector;
    this.size = size;
    union = new SynchronizedUnion((Union) SetOperation.builder().build(size, Family.UNION));
  }

  @Override
  public void aggregate()
  {
    Object update = selector.get();

    if(update == null) {
      return;
    }

    SetOpReturnState success;
    if (update instanceof Memory) {
      success = union.update((Memory) update);
    } else {
      success = union.update((Sketch) update);
    }

    if(success != SetOpReturnState.Success) {
      throw new IllegalStateException("Sketch Aggregation failed with state " + success);
    }
  }

  @Override
  public void reset()
  {
    union.reset();
  }

  @Override
  public Object get()
  {
    //in the code below, I am returning SetOp.getResult(true, null)
    //"true" returns an ordered sketch but slower to compute than unordered sketch.
    //however, advantage of ordered sketch is that they are faster to "union" later
    //given that results from the aggregator will be combined further, it is better
    //to return the ordered sketch here
    return union.getResult(true, null);
  }

  @Override
  public float getFloat()
  {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public long getLong()
  {
    throw new UnsupportedOperationException("Not implemented");
  }

  @Override
  public String getName()
  {
    return name;
  }

  @Override
  public void close()
  {
    union = null;
  }
}
