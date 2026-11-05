package com.carddemo.mq;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.carddemo.cics.CicsRegion;
import com.carddemo.cics.Extension;

/**
 * CKTI: starts the transid a queue's trigger definition names once a put to it commits, passing the MQTM. Each
 * triggered task runs on its own thread in its own transaction, as a START'ed CICS task does.
 */
@Component
public final class MqTriggerMonitor {

    private static final Logger log = LoggerFactory.getLogger(MqTriggerMonitor.class);

    private final ObjectProvider<TriggeredProgram> beans;
    private volatile Map<String, TriggeredProgram> programs;
    private final CicsRegion region;
    private final TransactionTemplate task;
    private final TaskExecutor executor = new SimpleAsyncTaskExecutor("mq-trigger-");

    /** The programs come from a provider because they put and get through the queues that fire this monitor. */
    MqTriggerMonitor(ObjectProvider<TriggeredProgram> programs, CicsRegion region,
            PlatformTransactionManager transactions) {
        this.beans = programs;
        this.region = region;
        this.task = new TransactionTemplate(transactions);
        this.task.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Fires {@code trigger} when the current transaction commits, or now when there is none. */
    void fireAfterCommit(Trigger trigger) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    executor.execute(() -> report(trigger, start(trigger)));
                }
            });
        } else {
            executor.execute(() -> report(trigger, start(trigger)));
        }
    }

    /** Runs the task {@code trigger} starts on this thread, in a transaction of its own, and says how it ended. */
    public TaskEnd start(Trigger trigger) {
        Optional<TriggeredProgram> program = Optional.ofNullable(programs().get(trigger.transid()))
                .filter(p -> Extension.owning(p.program()).map(region.installed()::contains).orElse(true));
        if (program.isEmpty()) {
            return new TaskEnd.NotDefined(trigger.transid());
        }
        try {
            task.executeWithoutResult(status -> program.get().run(trigger));
            return new TaskEnd.Normal();
        } catch (TaskEnd.AbendException abend) {
            return new TaskEnd.Abended(abend.code());
        } catch (RuntimeException failure) {
            log.warn("triggered task {} for queue {} failed", trigger.transid(), trigger.queue(), failure);
            return new TaskEnd.Abended("ASRA");
        }
    }

    private Map<String, TriggeredProgram> programs() {
        Map<String, TriggeredProgram> known = programs;
        if (known == null) {
            known = new HashMap<>();
            for (TriggeredProgram program : beans) {
                if (known.put(program.transid(), program) != null) {
                    throw new IllegalStateException("two triggered programs claim transid " + program.transid());
                }
            }
            programs = known;
        }
        return known;
    }

    private static void report(Trigger trigger, TaskEnd end) {
        if (!(end instanceof TaskEnd.Normal)) {
            log.warn("triggered task {} for queue {} ended {}", trigger.transid(), trigger.queue(), end);
        }
    }
}
