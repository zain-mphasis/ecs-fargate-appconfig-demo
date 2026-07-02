package com.mphasis.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LocalConfigurationServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsBundledConfigurationFile() {
        LocalConfigurationService service = new LocalConfigurationService(objectMapper);

        assertEquals("ECS Fargate AppConfig Demo", service.getConfiguration().get("applicationName"));
        assertEquals("local", service.getConfiguration().get("environment"));
        assertEquals("Local configuration file", service.getSource());
        assertNotNull(service.getLastUpdated());
    }

    @Test
    void failsFastWhenResourceIsMissing() {
        assertThrows(IllegalStateException.class,
                () -> new LocalConfigurationService(objectMapper, "does-not-exist.json"));
    }
}
