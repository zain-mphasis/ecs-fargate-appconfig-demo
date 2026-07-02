package com.mphasis.infra;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;

class AppConfigStackTest {

    @Test
    void definesApplicationEnvironmentProfileAndInitialDeployment() {
        AppConfigStack stack = new AppConfigStack(new App(), "TestAppConfigStack");
        Template template = Template.fromStack(stack);

        template.hasResourceProperties("AWS::AppConfig::Application", Map.of(
                "Name", "ecs-fargate-appconfig-demo"));
        template.hasResourceProperties("AWS::AppConfig::Environment", Map.of(
                "Name", "production"));
        template.hasResourceProperties("AWS::AppConfig::ConfigurationProfile", Map.of(
                "Name", "runtime-settings",
                "LocationUri", "hosted",
                "Type", "AWS.Freeform"));
        template.hasResourceProperties("AWS::AppConfig::DeploymentStrategy", Map.of(
                "DeploymentDurationInMinutes", 0,
                "GrowthFactor", 100,
                "ReplicateTo", "NONE"));
        template.hasResourceProperties("AWS::AppConfig::HostedConfigurationVersion", Map.of(
                "ContentType", "application/json"));
        template.resourceCountIs("AWS::AppConfig::Deployment", 1);
    }

    @Test
    void exposesIdentifiersForConsumingStacks() {
        AppConfigStack stack = new AppConfigStack(new App(), "TestAppConfigStack");

        assertNotNull(stack.getApplicationId());
        assertNotNull(stack.getEnvironmentId());
        assertNotNull(stack.getConfigurationProfileId());
    }

    @Test
    void initialConfigurationIsValidJsonWithExpectedKeys() {
        assertTrue(AppConfigStack.INITIAL_CONFIGURATION.contains("welcomeMessage"));
        assertTrue(AppConfigStack.INITIAL_CONFIGURATION.contains("applicationName"));
    }
}
