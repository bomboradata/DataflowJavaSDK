/*
 * Copyright (C) 2015 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.dataflow.sdk.util;


import com.google.cloud.dataflow.sdk.coders.Coder;
import com.google.cloud.dataflow.sdk.transforms.Combine.CombineFn;
import com.google.cloud.dataflow.sdk.transforms.Combine.KeyedCombineFn;
import com.google.cloud.dataflow.sdk.transforms.CombineWithContext.KeyedCombineFnWithContext;
import com.google.cloud.dataflow.sdk.transforms.GroupByKey;
import com.google.cloud.dataflow.sdk.transforms.windowing.BoundedWindow;
import com.google.cloud.dataflow.sdk.util.state.AccumulatorCombiningState;
import com.google.cloud.dataflow.sdk.util.state.BagState;
import com.google.cloud.dataflow.sdk.util.state.CombiningState;
import com.google.cloud.dataflow.sdk.util.state.MergingStateAccessor;
import com.google.cloud.dataflow.sdk.util.state.ReadableState;
import com.google.cloud.dataflow.sdk.util.state.StateAccessor;
import com.google.cloud.dataflow.sdk.util.state.StateMerging;
import com.google.cloud.dataflow.sdk.util.state.StateTag;
import com.google.cloud.dataflow.sdk.util.state.StateTags;

/**
 * {@link ReduceFn} implementing the default reduction behaviors of {@link GroupByKey}.
 *
 * @param <K> The type of key being processed.
 * @param <InputT> The type of values associated with the key.
 * @param <OutputT> The output type that will be produced for each key.
 * @param <W> The type of windows this operates on.
 */
public abstract class SystemReduceFn<K, InputT, AccumT, OutputT, W extends BoundedWindow>
    extends ReduceFn<K, InputT, OutputT, W> {
  private static final String BUFFER_NAME = "buf";

  /**
   * Create a factory that produces {@link SystemReduceFn} instances that that buffer all of the
   * input values in persistent state and produces an {@code Iterable<T>}.
   */
  public static <K, T, W extends BoundedWindow> SystemReduceFn<K, T, Iterable<T>, Iterable<T>, W>
      buffering(final Coder<T> inputCoder) {
    final StateTag<Object, BagState<T>> bufferTag =
        StateTags.makeSystemTagInternal(StateTags.bag(BUFFER_NAME, inputCoder));
    return new SystemReduceFn<K, T, Iterable<T>, Iterable<T>, W>(bufferTag) {
      @Override
      public void prefetchOnMerge(MergingStateAccessor<K, W> state) throws Exception {
        StateMerging.prefetchBags(state, bufferTag);
      }

      @Override
      public void onMerge(OnMergeContext c) throws Exception {
        StateMerging.mergeBags(c.state(), bufferTag);
      }
    };
  }

  /**
   * Create a factory that produces {@link SystemReduceFn} instances that combine all of the input
   * values using a {@link CombineFn}.
   */
  public static <K, InputT, AccumT, OutputT, W extends BoundedWindow> SystemReduceFn<K, InputT,
      AccumT, OutputT, W>
      combining(
          final Coder<K> keyCoder, final AppliedCombineFn<K, InputT, AccumT, OutputT> combineFn) {
    final StateTag<K, AccumulatorCombiningState<InputT, AccumT, OutputT>> bufferTag;
    if (combineFn.getFn() instanceof KeyedCombineFnWithContext) {
      bufferTag = StateTags.makeSystemTagInternal(
          StateTags.<K, InputT, AccumT, OutputT>keyedCombiningValueWithContext(
              BUFFER_NAME, combineFn.getAccumulatorCoder(),
              (KeyedCombineFnWithContext<K, InputT, AccumT, OutputT>) combineFn.getFn()));

    } else {
      bufferTag = StateTags.makeSystemTagInternal(
            StateTags.<K, InputT, AccumT, OutputT>keyedCombiningValue(
                BUFFER_NAME, combineFn.getAccumulatorCoder(),
                (KeyedCombineFn<K, InputT, AccumT, OutputT>) combineFn.getFn()));
    }
    return new SystemReduceFn<K, InputT, AccumT, OutputT, W>(bufferTag) {
      @Override
      public void prefetchOnMerge(MergingStateAccessor<K, W> state) throws Exception {
        StateMerging.prefetchCombiningValues(state, bufferTag);
      }

      @Override
      public void onMerge(OnMergeContext c) throws Exception {
        StateMerging.mergeCombiningValues(c.state(), bufferTag);
      }
    };
  }

  private StateTag<? super K, ? extends CombiningState<InputT, OutputT>> bufferTag;

  public SystemReduceFn(
      StateTag<? super K, ? extends CombiningState<InputT, OutputT>> bufferTag) {
    this.bufferTag = bufferTag;
  }

  @Override
  public void processValue(ProcessValueContext c) throws Exception {
    c.state().access(bufferTag).add(c.value());
  }

  @Override
  public void prefetchOnTrigger(StateAccessor<K> state) {
    state.access(bufferTag).readLater();
  }

  @Override
  public void onTrigger(OnTriggerContext c) throws Exception {
    c.output(c.state().access(bufferTag).read());
  }

  @Override
  public void clearState(Context c) throws Exception {
    c.state().access(bufferTag).clear();
  }

  @Override
  public ReadableState<Boolean> isEmpty(StateAccessor<K> state) {
    return state.access(bufferTag).isEmpty();
  }
}
