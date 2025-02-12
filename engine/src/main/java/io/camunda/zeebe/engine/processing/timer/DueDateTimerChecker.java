/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.camunda.zeebe.engine.processing.timer;

import io.camunda.zeebe.engine.processing.scheduled.DueDateChecker;
import io.camunda.zeebe.engine.processing.streamprocessor.ReadonlyProcessingContext;
import io.camunda.zeebe.engine.processing.streamprocessor.StreamProcessorLifecycleAware;
import io.camunda.zeebe.engine.processing.streamprocessor.writers.TypedCommandWriter;
import io.camunda.zeebe.engine.state.immutable.TimerInstanceState;
import io.camunda.zeebe.engine.state.immutable.TimerInstanceState.TimerVisitor;
import io.camunda.zeebe.engine.state.instance.TimerInstance;
import io.camunda.zeebe.protocol.impl.record.value.timer.TimerRecord;
import io.camunda.zeebe.protocol.record.intent.TimerIntent;
import io.camunda.zeebe.util.FeatureFlags;
import io.camunda.zeebe.util.sched.clock.ActorClock;
import java.time.Duration;
import java.util.function.Function;

public class DueDateTimerChecker implements StreamProcessorLifecycleAware {

  private static final long TIMER_RESOLUTION = Duration.ofMillis(100).toMillis();
  private static final double GIVE_YIELD_FACTOR = 0.5;
  private final DueDateChecker dueDateChecker;

  public DueDateTimerChecker(
      final TimerInstanceState timerInstanceState, final FeatureFlags featureFlags) {
    dueDateChecker =
        new DueDateChecker(
            TIMER_RESOLUTION,
            new TriggerTimersSideEffect(
                timerInstanceState, ActorClock.current(), featureFlags.yieldingDueDateChecker()));
  }

  public void scheduleTimer(final long dueDate) {
    dueDateChecker.schedule(dueDate);
  }

  @Override
  public void onRecovered(final ReadonlyProcessingContext context) {
    dueDateChecker.onRecovered(context);
  }

  @Override
  public void onClose() {
    dueDateChecker.onClose();
  }

  @Override
  public void onFailed() {
    dueDateChecker.onFailed();
  }

  @Override
  public void onPaused() {
    dueDateChecker.onPaused();
  }

  @Override
  public void onResumed() {
    dueDateChecker.onResumed();
  }

  protected static final class TriggerTimersSideEffect
      implements Function<TypedCommandWriter, Long> {

    private final ActorClock actorClock;

    private final TimerInstanceState timerInstanceState;
    private final boolean yieldControl;

    public TriggerTimersSideEffect(
        final TimerInstanceState timerInstanceState,
        final ActorClock actorClock,
        final boolean yieldControl) {
      this.timerInstanceState = timerInstanceState;
      this.actorClock = actorClock;
      this.yieldControl = yieldControl;
    }

    @Override
    public Long apply(final TypedCommandWriter typedCommandWriter) {
      final var now = actorClock.getTimeMillis();

      final var yieldAfter = now + Math.round(TIMER_RESOLUTION * GIVE_YIELD_FACTOR);

      final TimerVisitor timerVisitor;
      if (yieldControl) {
        timerVisitor =
            new YieldingDecorator(
                actorClock, yieldAfter, new WriteTriggerTimerCommandVisitor(typedCommandWriter));
      } else {
        timerVisitor = new WriteTriggerTimerCommandVisitor(typedCommandWriter);
      }

      return timerInstanceState.processTimersWithDueDateBefore(now, timerVisitor);
    }
  }

  protected static final class WriteTriggerTimerCommandVisitor implements TimerVisitor {

    private final TimerRecord timerRecord = new TimerRecord();

    private final TypedCommandWriter typedCommandWriter;

    public WriteTriggerTimerCommandVisitor(final TypedCommandWriter typedCommandWriter) {
      this.typedCommandWriter = typedCommandWriter;
    }

    @Override
    public boolean visit(final TimerInstance timer) {
      timerRecord.reset();
      timerRecord
          .setElementInstanceKey(timer.getElementInstanceKey())
          .setProcessInstanceKey(timer.getProcessInstanceKey())
          .setDueDate(timer.getDueDate())
          .setTargetElementId(timer.getHandlerNodeId())
          .setRepetitions(timer.getRepetitions())
          .setProcessDefinitionKey(timer.getProcessDefinitionKey());

      typedCommandWriter.reset();
      typedCommandWriter.appendFollowUpCommand(timer.getKey(), TimerIntent.TRIGGER, timerRecord);

      return typedCommandWriter.flush() > 0; // means the write was successful
    }
  }

  protected static final class YieldingDecorator implements TimerVisitor {

    private final TimerVisitor delegate;
    private final ActorClock actorClock;
    private final long giveYieldAfter;

    public YieldingDecorator(
        final ActorClock actorClock, final long giveYieldAfter, final TimerVisitor delegate) {
      this.delegate = delegate;
      this.actorClock = actorClock;
      this.giveYieldAfter = giveYieldAfter;
    }

    @Override
    public boolean visit(final TimerInstance timer) {
      if (actorClock.getTimeMillis() >= giveYieldAfter) {
        return false;
      }
      return delegate.visit(timer);
    }
  }
}
