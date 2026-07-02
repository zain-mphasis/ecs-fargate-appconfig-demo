package com.mphasis.demo.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

/**
 * Fallback used outside AWS (local development, tests): serves configuration from a
 * bundled JSON file so the page works without AppConfig connectivity.
 */
@Service
@Profile("!aws")
public class LocalConfigurationService implements ConfigurationService {

    private final Map<String, Object> configuration;
    private final Instant loadedAt;

    @Autowired
    public LocalConfigurationService(ObjectMapper objectMapper) {
        this(objectMapper, "local-config.json");
    }

    LocalConfigurationService(ObjectMapper objectMapper, String resourceName) {
        try (InputStream input = new ClassPathResource(resourceName).getInputStream()) {
            this.configuration = objectMapper.readValue(input, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            throw new IllegalStateException("Unable to load local configuration from " + resourceName, e);
        }
        this.loadedAt = Instant.now();
    }

    @Override
    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    @Override
    public String getSource() {
        return "Local configuration file";
    }

    @Override
    public Instant getLastUpdated() {
        return loadedAt;
    }
}
