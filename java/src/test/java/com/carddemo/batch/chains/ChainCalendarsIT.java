package com.carddemo.batch.chains;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;

import com.carddemo.CarddemoApplication;
import com.carddemo.TestDatabase;
import com.carddemo.batch.library.LibraryJob;
import com.carddemo.cics.CicsRegion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Calendars as an operator sets them, in the application's own startup. */
class ChainCalendarsIT {

    private static final String CHAIN = "CALENDAR_" + Long.toString(System.currentTimeMillis(), 36).toUpperCase();

    @TestConfiguration
    static class CalendarChain {
        @Bean
        @Primary
        Chains calendarChains() {
            return Chains.of(new Chain(CHAIN, Chain.Scheduler.CONTROL_M, "TEST",
                    List.of(new Member.Job(LibraryJob.CLOSEFIL), new Member.Job(LibraryJob.OPENFIL)), "test",
                    Optional.empty()));
        }
    }

    @Test
    void noCalendarIsInstalledByDefault() {
        try (ConfigurableApplicationContext app = app().run()) {
            assertEquals(Map.of(), app.getBean(ChainCalendars.class).installed());
        }
    }

    @TestConfiguration
    static class UnportedCalendar {
        @Bean
        @Primary
        Chains unportedChains() {
            return Chains.of(new Chain("UNPORTED_CAL", Chain.Scheduler.CA7, "TEST",
                    List.of(new Member.Unported("NOTREAL", "not ported")), "test", Optional.empty()));
        }
    }

    @Test
    void aCalendarForAChainThatCannotRunFailsStartup() {
        Exception refused = assertThrows(Exception.class,
                () -> app("carddemo.batch.calendar.UNPORTED_CAL=0 0 1 * * *").sources(UnportedCalendar.class)
                        .run().close());

        assertTrue(causes(refused).contains(
                "carddemo.batch.calendar.UNPORTED_CAL: UNPORTED_CAL cannot run, [NOTREAL] not ported"),
                causes(refused).toString());
    }

    @Test
    void aCalendarNamingNoChainFailsStartup() {
        Exception refused = assertThrows(Exception.class,
                () -> app("carddemo.batch.calendar.CA7_030_POSTNG=0 0 1 * * *").run().close());

        assertTrue(causes(refused).contains("carddemo.batch.calendar.CA7_030_POSTNG names no chain"),
                causes(refused).toString());
    }

    @Test
    void anInstalledCalendarStartsTheChainForTheDayAtItsFire() throws Exception {
        try (ConfigurableApplicationContext app = app("carddemo.batch.calendar." + CHAIN + "=* * * * * *")
                .sources(CalendarChain.class).run()) {
            ChainRunner runner = app.getBean(ChainRunner.class);
            Chain chain = app.getBean(Chains.class).byName(CHAIN).orElseThrow();
            LocalDate today = LocalDate.now(app.getBean(Clock.class));
            assertEquals(Map.of(CHAIN, "* * * * * *"), app.getBean(ChainCalendars.class).installed());

            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (runner.run(chain, today).filter(ChainRun::completed).isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }

            assertTrue(runner.run(chain, today).filter(ChainRun::completed).isPresent(), "the calendar fired");
            assertEquals(Set.of(), app.getBean(CicsRegion.class).closed());
        }
    }

    /** Scans what production scans: the test configurations of other test classes stay out. */
    private static final class ProductionClasses extends TypeExcludeFilter {
        @Override
        public boolean match(MetadataReader reader, MetadataReaderFactory factory) throws IOException {
            return reader.getResource().getURI().toString().contains("/test-classes/");
        }
    }

    private static SpringApplicationBuilder app(String... properties) {
        List<String> all = new ArrayList<>(List.of(properties));
        TestDatabase.use((name, value) -> all.add(name + "=" + value.get()));
        return new SpringApplicationBuilder(CarddemoApplication.class).web(WebApplicationType.NONE)
                .initializers(context -> context.getBeanFactory()
                        .registerSingleton(ProductionClasses.class.getName(), new ProductionClasses()))
                .properties(all.toArray(String[]::new));
    }

    private static List<String> causes(Throwable failure) {
        List<String> messages = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            messages.add(cause.getMessage());
        }
        return messages;
    }
}
