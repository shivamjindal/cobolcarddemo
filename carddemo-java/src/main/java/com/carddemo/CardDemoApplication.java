package com.carddemo;

import com.carddemo.vsam.SeedFormat;
import com.carddemo.vsam.SeedLoader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.nio.file.Path;

@SpringBootApplication
public class CardDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(CardDemoApplication.class, args);
    }

    @Bean
    @ConditionalOnProperty("carddemo.seed.dir")
    CommandLineRunner seed(SeedLoader loader,
                           @Value("${carddemo.seed.dir}") Path dir,
                           @Value("${carddemo.seed.format:EBCDIC}") SeedFormat format) {
        return args -> loader.load(dir, format);
    }
}
