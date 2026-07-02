package com.mphasis.demo.service;

import java.time.Instant;
import java.util.Map;

/**
 * Provides the configuration key/value pairs rendered on the configuration page.
 */
public interface ConfigurationService {

    Map<String, Object> getConfiguration();

    String getSource();

    Instant getLastUpdated();
}
