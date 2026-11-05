package com.carddemo.characterization;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.web.filter.OncePerRequestFilter;

/** What the adapter's warm server needs and production must not have. Only under profile "characterization". */
@Configuration
@Profile("characterization")
class CharacterizationSupport {

    @Bean
    @Primary
    Clock characterizationClock(@Value("${carddemo.characterization.clock}") LocalDateTime at) {
        return Clock.fixed(at.atZone(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
    }

    @Bean
    IdleShutdown idleShutdown(@Value("${carddemo.characterization.idle-shutdown}") Duration idle,
            ConfigurableApplicationContext context) {
        return new IdleShutdown(idle, context);
    }

    /** Closes the server after a stretch without requests, so a warm server never outlives its runs by much. */
    static final class IdleShutdown extends OncePerRequestFilter {

        private static final Logger log = LoggerFactory.getLogger(IdleShutdown.class);

        private volatile long lastRequest = System.nanoTime();

        IdleShutdown(Duration idle, ConfigurableApplicationContext context) {
            ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "idle-shutdown");
                thread.setDaemon(true);
                return thread;
            });
            timer.scheduleWithFixedDelay(() -> {
                if (System.nanoTime() - lastRequest > idle.toNanos()) {
                    log.info("no request for {}; shutting down", idle);
                    timer.shutdown();
                    context.close();
                }
            }, 10, 10, TimeUnit.SECONDS);
        }

        @Override
        protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            lastRequest = System.nanoTime();
            chain.doFilter(request, response);
        }
    }
}
