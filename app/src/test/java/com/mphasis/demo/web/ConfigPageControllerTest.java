package com.mphasis.demo.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

import com.mphasis.demo.service.ConfigurationService;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ExtendedModelMap;

@ExtendWith(MockitoExtension.class)
class ConfigPageControllerTest {

    private static final Instant UPDATED_AT = Instant.parse("2026-07-01T10:15:30Z");

    @Mock
    private ConfigurationService configurationService;

    private ConfigPageController controller;

    @BeforeEach
    void setUp() {
        when(configurationService.getConfiguration()).thenReturn(Map.of("welcomeMessage", "hello"));
        when(configurationService.getSource()).thenReturn("AWS AppConfig");
        when(configurationService.getLastUpdated()).thenReturn(UPDATED_AT);
        controller = new ConfigPageController(configurationService);
    }

    @Test
    void configurationPagePopulatesModel() {
        ExtendedModelMap model = new ExtendedModelMap();

        String view = controller.configurationPage(model);

        assertEquals("config", view);
        assertEquals(Map.of("welcomeMessage", "hello"), model.getAttribute("configuration"));
        assertEquals("AWS AppConfig", model.getAttribute("source"));
        assertEquals(UPDATED_AT, model.getAttribute("lastUpdated"));
    }

    @Test
    void apiEndpointReturnsConfigurationEnvelope() {
        Map<String, Object> body = controller.configuration();

        assertEquals("AWS AppConfig", body.get("source"));
        assertEquals(UPDATED_AT.toString(), body.get("lastUpdated"));
        assertEquals(Map.of("welcomeMessage", "hello"), body.get("configuration"));
    }
}
