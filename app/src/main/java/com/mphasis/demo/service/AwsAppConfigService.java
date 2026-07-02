package com.mphasis.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mphasis.demo.config.AppConfigSettings;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationRequest;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest;

/**
 * Reads configuration from AWS AppConfig using the AppConfigData session API.
 * {@link #refresh()} is invoked periodically by {@link ConfigRefreshScheduler}; when a new
 * deployment happens in AppConfig the next poll returns the fresh payload and the page
 * reflects it without a restart.
 */
@Service
@Profile("aws")
public class AwsAppConfigService implements ConfigurationService {

    private static final Logger LOG = LoggerFactory.getLogger(AwsAppConfigService.class);

    private final AppConfigDataClient appConfigDataClient;
    private final AppConfigSettings settings;
    private final ObjectMapper objectMapper;
    private final AtomicReference<Map<String, Object>> configuration =
            new AtomicReference<>(Collections.emptyMap());
    private final AtomicReference<Instant> lastUpdated = new AtomicReference<>(Instant.now());
    private String configurationToken;

    public AwsAppConfigService(AppConfigDataClient appConfigDataClient,
                               AppConfigSettings settings,
                               ObjectMapper objectMapper) {
        this.appConfigDataClient = appConfigDataClient;
        this.settings = settings;
        this.objectMapper = objectMapper;
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return configuration.get();
    }

    @Override
    public String getSource() {
        return "AWS AppConfig";
    }

    @Override
    public Instant getLastUpdated() {
        return lastUpdated.get();
    }

    public synchronized void refresh() {
        try {
            if (configurationToken == null) {
                configurationToken = appConfigDataClient.startConfigurationSession(
                        StartConfigurationSessionRequest.builder()
                                .applicationIdentifier(settings.getApplicationId())
                                .environmentIdentifier(settings.getEnvironmentId())
                                .configurationProfileIdentifier(settings.getConfigurationProfileId())
                                .build())
                        .initialConfigurationToken();
            }
            GetLatestConfigurationResponse response = appConfigDataClient.getLatestConfiguration(
                    GetLatestConfigurationRequest.builder()
                            .configurationToken(configurationToken)
                            .build());
            configurationToken = response.nextPollConfigurationToken();
            byte[] payload = response.configuration().asByteArray();
            // AppConfig returns an empty payload when the configuration has not changed
            // since the previous poll of this session.
            if (payload.length > 0) {
                configuration.set(objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() { }));
                lastUpdated.set(Instant.now());
                LOG.info("Applied new configuration from AWS AppConfig ({} keys)", configuration.get().size());
            }
        } catch (Exception e) {
            // Session tokens are single-use; drop the session so the next poll starts a new one.
            configurationToken = null;
            LOG.warn("Failed to refresh configuration from AWS AppConfig: {}", e.getMessage());
        }
    }
}
