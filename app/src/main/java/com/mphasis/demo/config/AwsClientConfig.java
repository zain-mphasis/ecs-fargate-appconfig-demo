package com.mphasis.demo.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;

@Configuration
@Profile("aws")
public class AwsClientConfig {

    @Bean
    public AppConfigDataClient appConfigDataClient(@Value("${aws.region:us-east-1}") String region) {
        return AppConfigDataClient.builder()
                .region(Region.of(region))
                .build();
    }
}
