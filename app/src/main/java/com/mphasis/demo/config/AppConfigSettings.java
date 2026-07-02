package com.mphasis.demo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Identifiers of the AWS AppConfig application / environment / configuration profile
 * this service reads its runtime configuration from.
 */
@Component
@ConfigurationProperties(prefix = "appconfig")
public class AppConfigSettings {

    private String applicationId = "";
    private String environmentId = "";
    private String configurationProfileId = "";
    private long pollIntervalSeconds = 30;

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public String getEnvironmentId() {
        return environmentId;
    }

    public void setEnvironmentId(String environmentId) {
        this.environmentId = environmentId;
    }

    public String getConfigurationProfileId() {
        return configurationProfileId;
    }

    public void setConfigurationProfileId(String configurationProfileId) {
        this.configurationProfileId = configurationProfileId;
    }

    public long getPollIntervalSeconds() {
        return pollIntervalSeconds;
    }

    public void setPollIntervalSeconds(long pollIntervalSeconds) {
        this.pollIntervalSeconds = pollIntervalSeconds;
    }
}
