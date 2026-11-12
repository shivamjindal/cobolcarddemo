package com.carddemo;

import com.carddemo.batch.BatchJobs;
import com.carddemo.batch.DailyPosting;
import com.carddemo.vsam.SeedFormat;
import com.carddemo.vsam.SeedLoader;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;
import java.time.Clock;
import java.util.UUID;

@SpringBootApplication
public class CardDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }

    @Bean
    Clock clock() {
        return Clock.systemDefaultZone();
    }

    // Bound as a String: in a web context Spring would resolve a Path against the servlet context and reject "../".
    @Bean
    @ConditionalOnProperty("carddemo.seed.dir")
    CommandLineRunner seed(SeedLoader loader, BatchJobs jobs,
                           @Value("${carddemo.seed.dir}") String dir,
                           @Value("${carddemo.seed.format:EBCDIC}") SeedFormat format) {
        return args -> {
            loader.load(Path.of(dir), format);
            jobs.run("POSTTRAN", new JobParametersBuilder()
                    .addString(DailyPosting.FEED, format.dailyTransactionsFile(Path.of(dir)).toString())
                    .addString(DailyPosting.FORMAT, format.name())
                    .addString("seed", UUID.randomUUID().toString())
                    .toJobParameters());
        };
    }
}
