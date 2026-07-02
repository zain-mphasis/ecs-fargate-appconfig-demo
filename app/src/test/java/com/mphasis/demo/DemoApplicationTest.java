package com.mphasis.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class DemoApplicationTest {

    @Test
    void mainDelegatesToSpringApplication() {
        try (MockedStatic<SpringApplication> springApplication = mockStatic(SpringApplication.class)) {
            String[] args = {"--spring.profiles.active=test"};

            DemoApplication.main(args);

            springApplication.verify(() -> SpringApplication.run(DemoApplication.class, args));
        }
    }

    @Test
    void canBeInstantiated() {
        assertNotNull(new DemoApplication());
    }
}
