package com.mphasis.demo.service;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ConfigRefreshSchedulerTest {

    @Mock
    private AwsAppConfigService awsAppConfigService;

    @Test
    void delegatesRefreshToService() {
        new ConfigRefreshScheduler(awsAppConfigService).refreshConfiguration();

        verify(awsAppConfigService).refresh();
    }
}
