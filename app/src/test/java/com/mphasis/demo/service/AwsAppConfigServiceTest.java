package com.mphasis.demo.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.appconfigdata.AppConfigDataClient;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationRequest;
import software.amazon.awssdk.services.appconfigdata.model.GetLatestConfigurationResponse;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionRequest;
import software.amazon.awssdk.services.appconfigdata.model.StartConfigurationSessionResponse;

@ExtendWith(MockitoExtension.class)
class AwsAppConfigServiceTest {

    @Mock
    private AppConfigDataClient appConfigDataClient;

    private AwsAppConfigService service;

    @BeforeEach
    void setUp() {
        var settings = new com.mphasis.demo.config.AppConfigSettings();
        settings.setApplicationId("app-1");
        settings.setEnvironmentId("env-1");
        settings.setConfigurationProfileId("profile-1");
        service = new AwsAppConfigService(appConfigDataClient, settings, new ObjectMapper());
    }

    private void stubSession(String initialToken) {
        when(appConfigDataClient.startConfigurationSession(any(StartConfigurationSessionRequest.class)))
                .thenReturn(StartConfigurationSessionResponse.builder()
                        .initialConfigurationToken(initialToken)
                        .build());
    }

    private GetLatestConfigurationResponse response(String nextToken, String json) {
        return GetLatestConfigurationResponse.builder()
                .nextPollConfigurationToken(nextToken)
                .configuration(SdkBytes.fromUtf8String(json))
                .build();
    }

    @Test
    void exposesEmptyConfigurationBeforeFirstRefresh() {
        assertTrue(service.getConfiguration().isEmpty());
        assertEquals("AWS AppConfig", service.getSource());
        assertNotNull(service.getLastUpdated());
    }

    @Test
    void firstRefreshStartsSessionAndAppliesPayload() {
        stubSession("token-1");
        when(appConfigDataClient.getLatestConfiguration(any(GetLatestConfigurationRequest.class)))
                .thenReturn(response("token-2", "{\"welcomeMessage\":\"hello\",\"maxItemsPerPage\":50}"));

        service.refresh();

        assertEquals("hello", service.getConfiguration().get("welcomeMessage"));
        assertEquals(50, service.getConfiguration().get("maxItemsPerPage"));

        ArgumentCaptor<StartConfigurationSessionRequest> captor =
                ArgumentCaptor.forClass(StartConfigurationSessionRequest.class);
        verify(appConfigDataClient).startConfigurationSession(captor.capture());
        assertEquals("app-1", captor.getValue().applicationIdentifier());
        assertEquals("env-1", captor.getValue().environmentIdentifier());
        assertEquals("profile-1", captor.getValue().configurationProfileIdentifier());
    }

    @Test
    void subsequentRefreshReusesSessionWithNextToken() {
        stubSession("token-1");
        when(appConfigDataClient.getLatestConfiguration(any(GetLatestConfigurationRequest.class)))
                .thenReturn(response("token-2", "{\"a\":1}"))
                .thenReturn(response("token-3", "{\"a\":2}"));

        service.refresh();
        service.refresh();

        verify(appConfigDataClient, times(1)).startConfigurationSession(any(StartConfigurationSessionRequest.class));

        ArgumentCaptor<GetLatestConfigurationRequest> captor =
                ArgumentCaptor.forClass(GetLatestConfigurationRequest.class);
        verify(appConfigDataClient, times(2)).getLatestConfiguration(captor.capture());
        assertEquals("token-1", captor.getAllValues().get(0).configurationToken());
        assertEquals("token-2", captor.getAllValues().get(1).configurationToken());
        assertEquals(2, service.getConfiguration().get("a"));
    }

    @Test
    void emptyPayloadKeepsPreviousConfiguration() {
        stubSession("token-1");
        when(appConfigDataClient.getLatestConfiguration(any(GetLatestConfigurationRequest.class)))
                .thenReturn(response("token-2", "{\"a\":1}"))
                .thenReturn(response("token-3", ""));

        service.refresh();
        service.refresh();

        assertEquals(Map.of("a", 1), service.getConfiguration());
    }

    @Test
    void failureResetsSessionSoNextRefreshStartsANewOne() {
        stubSession("token-1");
        when(appConfigDataClient.getLatestConfiguration(any(GetLatestConfigurationRequest.class)))
                .thenThrow(new RuntimeException("network error"))
                .thenReturn(response("token-2", "{\"recovered\":true}"));

        service.refresh();
        assertTrue(service.getConfiguration().isEmpty());

        service.refresh();
        assertEquals(true, service.getConfiguration().get("recovered"));

        verify(appConfigDataClient, times(2)).startConfigurationSession(any(StartConfigurationSessionRequest.class));
    }
}
