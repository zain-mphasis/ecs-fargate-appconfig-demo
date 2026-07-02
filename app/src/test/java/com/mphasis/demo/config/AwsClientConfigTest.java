package com.mphasis.demo.config;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;

class AwsClientConfigTest {

    @Test
    void buildsClientForConfiguredRegion() {
        try (AppConfigDataClient client = new AwsClientConfig().appConfigDataClient("eu-west-1")) {
            assertNotNull(client);
        }
    }
}
