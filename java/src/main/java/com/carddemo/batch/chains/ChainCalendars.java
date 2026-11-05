package com.carddemo.batch.chains;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Component;

/**
 * The calendars an operator installs: {@code carddemo.batch.calendar.<CHAIN>=<cron>} starts the chain for that
 * day's order date at each fire. None is installed by default. A calendar naming no chain, a chain that cannot
 * run, or a malformed cron fails startup. Fires run one at a time on one thread, so a chain still running when
 * another's calendar fires delays it.
 */
@Component
public final class ChainCalendars implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ChainCalendars.class);
    private static final String PREFIX = "carddemo.batch.calendar";

    private final ChainRunner runner;
    private final Clock clock;
    private final Map<Chain, String> calendars = new LinkedHashMap<>();
    private ThreadPoolTaskScheduler scheduler;

    ChainCalendars(Environment environment, Chains chains, ChainRunner runner, Clock clock) {
        this.runner = runner;
        this.clock = clock;
        Binder.get(environment).bind(PREFIX, Bindable.mapOf(String.class, String.class)).orElse(Map.of())
                .forEach((key, cron) -> {
                    String name = key.toUpperCase(Locale.ROOT).replace('.', '_').replace('-', '_');
                    Chain chain = chains.byName(name).orElseThrow(
                            () -> new IllegalStateException(PREFIX + "." + key + " names no chain"));
                    if (!chain.runnable()) {
                        throw new IllegalStateException(PREFIX + "." + key + ": " + name + " cannot run, "
                                + chain.unported().stream().map(Member::name).toList() + " not ported");
                    }
                    if (!CronExpression.isValidExpression(cron)) {
                        throw new IllegalStateException(PREFIX + "." + key + ": '" + cron + "' is not a cron");
                    }
                    calendars.put(chain, cron);
                });
    }

    /** Each installed chain's cron, by chain name. */
    public Map<String, String> installed() {
        Map<String, String> installed = new LinkedHashMap<>();
        calendars.forEach((chain, cron) -> installed.put(chain.name(), cron));
        return Collections.unmodifiableMap(installed);
    }

    @EventListener(ApplicationReadyEvent.class)
    void install() {
        if (calendars.isEmpty()) {
            return;
        }
        scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("chain-calendar-");
        scheduler.initialize();
        calendars.forEach((chain, cron) -> {
            scheduler.schedule(() -> fire(chain), new CronTrigger(cron, clock.getZone()));
            log.info("calendar {} installed: {}", chain.name(), cron);
        });
    }

    @Override
    public void destroy() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    private void fire(Chain chain) {
        LocalDate orderDate = LocalDate.now(clock);
        try {
            ChainRun run = runner.start(chain, orderDate);
            log.info("{} for {} {}", chain.name(), orderDate, run.completed() ? "completed" : "stopped: " + run.members());
        } catch (ChainRunner.Refused e) {
            log.warn("calendar {}: {}", chain.name(), e.getMessage());
        } catch (RuntimeException e) {
            log.error("calendar {}: the run for {} failed", chain.name(), orderDate, e);
        }
    }
}
