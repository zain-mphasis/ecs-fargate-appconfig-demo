package com.mphasis.demo.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppConfigSettingsTest {

    @Test
    void defaultsAreSafe() {
        AppConfigSettings settings = new AppConfigSettings();

        assertEquals("", settings.getApplicationId());
        assertEquals("", settings.getEnvironmentId());
        assertEquals("", settings.getConfigurationProfileId());
        assertEquals(30, settings.getPollIntervalSeconds());
    }

    @Test
    void settersUpdateAllFields() {
        AppConfigSettings settings = new AppConfigSettings();

        settings.setApplicationId("app-1");
        settings.setEnvironmentId("env-1");
        settings.setConfigurationProfileId("profile-1");
        settings.setPollIntervalSeconds(15);

        assertEquals("app-1", settings.getApplicationId());
        assertEquals("env-1", settings.getEnvironmentId());
        assertEquals("profile-1", settings.getConfigurationProfileId());
        assertEquals(15, settings.getPollIntervalSeconds());
    }
}
