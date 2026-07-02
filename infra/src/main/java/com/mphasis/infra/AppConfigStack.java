package com.mphasis.infra;

import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.appconfig.CfnApplication;
import software.amazon.awscdk.services.appconfig.CfnConfigurationProfile;
import software.amazon.awscdk.services.appconfig.CfnDeployment;
import software.amazon.awscdk.services.appconfig.CfnDeploymentStrategy;
import software.amazon.awscdk.services.appconfig.CfnEnvironment;
import software.amazon.awscdk.services.appconfig.CfnHostedConfigurationVersion;
import software.constructs.Construct;

/**
 * AWS AppConfig resources, fully managed as code: application, environment, freeform
 * hosted configuration profile, an all-at-once deployment strategy and the initial
 * configuration version + deployment. Later configuration changes are made by creating
 * a new hosted configuration version and starting a deployment (see README).
 */
public class AppConfigStack extends Stack {

    static final String INITIAL_CONFIGURATION = """
            {
              "applicationName": "ECS Fargate AppConfig Demo",
              "environment": "production",
              "welcomeMessage": "Hello from AWS AppConfig!",
              "featureDarkModeEnabled": true,
              "maxItemsPerPage": 50,
              "supportContact": "zain.akhtar1@mphasis.com"
            }
            """;

    private final CfnApplication application;
    private final CfnEnvironment environment;
    private final CfnConfigurationProfile configurationProfile;

    public AppConfigStack(final Construct scope, final String id) {
        super(scope, id, StackProps.builder()
                .description("AWS AppConfig application, environment and configuration profile")
                .build());

        this.application = CfnApplication.Builder.create(this, "Application")
                .name("ecs-fargate-appconfig-demo")
                .description("Runtime configuration for the ECS Fargate demo application")
                .build();

        this.environment = CfnEnvironment.Builder.create(this, "Environment")
                .applicationId(application.getRef())
                .name("production")
                .build();

        this.configurationProfile = CfnConfigurationProfile.Builder.create(this, "Profile")
                .applicationId(application.getRef())
                .name("runtime-settings")
                .locationUri("hosted")
                .type("AWS.Freeform")
                .build();

        CfnDeploymentStrategy deploymentStrategy = CfnDeploymentStrategy.Builder.create(this, "AllAtOnce")
                .name("ecs-fargate-demo-all-at-once")
                .deploymentDurationInMinutes(0)
                .growthFactor(100)
                .finalBakeTimeInMinutes(0)
                .replicateTo("NONE")
                .build();

        CfnHostedConfigurationVersion initialVersion =
                CfnHostedConfigurationVersion.Builder.create(this, "InitialVersion")
                        .applicationId(application.getRef())
                        .configurationProfileId(configurationProfile.getRef())
                        .contentType("application/json")
                        .content(INITIAL_CONFIGURATION)
                        .build();

        CfnDeployment.Builder.create(this, "InitialDeployment")
                .applicationId(application.getRef())
                .environmentId(environment.getRef())
                .configurationProfileId(configurationProfile.getRef())
                .configurationVersion(initialVersion.getRef())
                .deploymentStrategyId(deploymentStrategy.getRef())
                .build();

        CfnOutput.Builder.create(this, "ApplicationId").value(application.getRef()).build();
        CfnOutput.Builder.create(this, "EnvironmentId").value(environment.getRef()).build();
        CfnOutput.Builder.create(this, "ConfigurationProfileId").value(configurationProfile.getRef()).build();
    }

    public String getApplicationId() {
        return application.getRef();
    }

    public String getEnvironmentId() {
        return environment.getRef();
    }

    public String getConfigurationProfileId() {
        return configurationProfile.getRef();
    }
}
