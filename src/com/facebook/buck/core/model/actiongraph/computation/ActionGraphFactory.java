/*
 * Copyright 2018-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.facebook.buck.core.model.actiongraph.computation;

import com.facebook.buck.core.cell.CellProvider;
import com.facebook.buck.core.model.actiongraph.ActionGraphAndBuilder;
import com.facebook.buck.core.model.targetgraph.TargetGraph;
import com.facebook.buck.core.rules.ActionGraphBuilder;
import com.facebook.buck.core.rules.transformer.TargetNodeToBuildRuleTransformer;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ExperimentEvent;
import com.facebook.buck.util.randomizedtrial.RandomizedTrial;
import com.google.common.base.Preconditions;
import java.util.Map;

public class ActionGraphFactory {

  public static ActionGraphFactory create(
      BuckEventBus eventBus, ParallelActionGraphFactory parallelActionGraphFactory) {
    return new ActionGraphFactory(
        eventBus, parallelActionGraphFactory, new SerialActionGraphFactory(eventBus));
  }

  private final BuckEventBus eventBus;
  private final ParallelActionGraphFactory parallelActionGraphFactory;
  private final SerialActionGraphFactory serialActionGraphFactory;

  ActionGraphFactory(
      BuckEventBus eventBus,
      ParallelActionGraphFactory parallelActionGraphFactory,
      SerialActionGraphFactory serialActionGraphFactory) {
    this.eventBus = eventBus;
    this.parallelActionGraphFactory = parallelActionGraphFactory;
    this.serialActionGraphFactory = serialActionGraphFactory;
  }

  public ActionGraphAndBuilder createActionGraph(
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      ActionGraphParallelizationMode parallelizationMode,
      boolean shouldInstrumentGraphBuilding,
      IncrementalActionGraphMode incrementalActionGraphMode,
      Map<IncrementalActionGraphMode, Double> incrementalActionGraphExperimentGroups,
      ActionGraphCreationLifecycleListener actionGraphCreationLifecycleListener) {

    if (incrementalActionGraphMode == IncrementalActionGraphMode.EXPERIMENT) {
      incrementalActionGraphMode =
          RandomizedTrial.getGroupStable(
              "incremental_action_graph", incrementalActionGraphExperimentGroups);
      Preconditions.checkState(incrementalActionGraphMode != IncrementalActionGraphMode.EXPERIMENT);
      eventBus.post(
          new ExperimentEvent(
              "incremental_action_graph", incrementalActionGraphMode.toString(), "", null, null));
    }

    ActionGraphCreationLifecycleListener listener;
    if (incrementalActionGraphMode == IncrementalActionGraphMode.ENABLED) {
      listener = actionGraphCreationLifecycleListener;
    } else {
      listener = graphBuilder -> {};
    }
    return createActionGraph(
        transformer,
        targetGraph,
        cellProvider,
        parallelizationMode,
        shouldInstrumentGraphBuilding,
        listener);
  }

  private ActionGraphAndBuilder createActionGraph(
      TargetNodeToBuildRuleTransformer transformer,
      TargetGraph targetGraph,
      CellProvider cellProvider,
      ActionGraphParallelizationMode parallelizationMode,
      boolean shouldInstrumentGraphBuilding,
      ActionGraphCreationLifecycleListener actionGraphCreationLifecycleListener) {

    switch (parallelizationMode) {
      case EXPERIMENT:
        parallelizationMode =
            RandomizedTrial.getGroupStable(
                "action_graph_parallelization", ActionGraphParallelizationMode.class);
        eventBus.post(
            new ExperimentEvent(
                "action_graph_parallelization", parallelizationMode.toString(), "", null, null));
        break;
      case EXPERIMENT_UNSTABLE:
        parallelizationMode =
            RandomizedTrial.getGroup(
                "action_graph_parallelization",
                eventBus.getBuildId().toString(),
                ActionGraphParallelizationMode.class);
        eventBus.post(
            new ExperimentEvent(
                "action_graph_parallelization_unstable",
                parallelizationMode.toString(),
                "",
                null,
                null));
        break;
      case ENABLED:
      case DISABLED:
        break;
    }
    switch (parallelizationMode) {
      case ENABLED:
        return parallelActionGraphFactory.create(
            ParallelActionGraphCreationParameters.of(
                transformer, targetGraph, cellProvider, actionGraphCreationLifecycleListener));
      case DISABLED:
        return serialActionGraphFactory.create(
            SerialActionGraphCreationParameters.of(
                transformer,
                targetGraph,
                cellProvider,
                shouldInstrumentGraphBuilding,
                actionGraphCreationLifecycleListener));
      case EXPERIMENT_UNSTABLE:
      case EXPERIMENT:
        throw new AssertionError(
            "EXPERIMENT* values should have been resolved to ENABLED or DISABLED.");
    }
    throw new AssertionError("Unexpected parallelization mode value: " + parallelizationMode);
  }

  interface ActionGraphCreationLifecycleListener {
    void onCreate(ActionGraphBuilder graphBuilder);
  }
}
