package com.carddemo;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertFalse;

/** The load profile runs with no web server, so nothing outside the online stack may need one. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NonWebContextIT {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        TestDatabase.use(properties);
    }

    @Autowired
    private ApplicationContext context;

    @Test
    void startsWithoutTheOnlineStack() {
        assertFalse(context.containsBean("dispatcher"));
    }
}
