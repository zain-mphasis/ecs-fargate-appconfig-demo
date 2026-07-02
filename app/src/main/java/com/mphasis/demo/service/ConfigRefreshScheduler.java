package com.mphasis.demo.service;

import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile("aws")
public class ConfigRefreshScheduler {

    private final AwsAppConfigService awsAppConfigService;

    public ConfigRefreshScheduler(AwsAppConfigService awsAppConfigService) {
        this.awsAppConfigService = awsAppConfigService;
    }

    @Scheduled(initialDelay = 0,
            fixedDelayString = "${appconfig.poll-interval-seconds:30}",
            timeUnit = TimeUnit.SECONDS)
    public void refreshConfiguration() {
        awsAppConfigService.refresh();
    }
}
