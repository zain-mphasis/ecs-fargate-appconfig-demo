package com.mphasis.demo;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mphasis.demo.service.ConfigurationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ApplicationContextTest {

    @Autowired
    private ConfigurationService configurationService;

    @Test
    void contextLoadsWithLocalConfigurationService() {
        assertNotNull(configurationService);
        assertNotNull(configurationService.getConfiguration());
    }
}
