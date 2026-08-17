package com.mphasis.infra;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Duration;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.appconfig.CfnApplication;
import software.amazon.awscdk.services.appconfig.CfnConfigurationProfile;
import software.amazon.awscdk.services.appconfig.CfnDeployment;
import software.amazon.awscdk.services.appconfig.CfnDeploymentStrategy;
import software.amazon.awscdk.services.appconfig.CfnEnvironment;
import software.amazon.awscdk.services.appconfig.CfnHostedConfigurationVersion;
import software.amazon.awscdk.services.cloudwatch.Alarm;
import software.amazon.awscdk.services.cloudwatch.ComparisonOperator;
import software.amazon.awscdk.services.cloudwatch.Metric;
import software.amazon.awscdk.services.cloudwatch.TreatMissingData;
import software.amazon.awscdk.services.iam.PolicyDocument;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.amazon.awscdk.services.iam.Role;
import software.amazon.awscdk.services.iam.ServicePrincipal;
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

        // Auto-rollback guardrail: a CloudWatch alarm AppConfig watches during a deployment's
        // bake time. If it fires while a new config is rolling out, AppConfig automatically
        // rolls back to the previous configuration. (Points at the ECS service by name so it
        // needs no cross-stack dependency; in production you would point it at your app's
        // error-rate metric.)
        Alarm rollbackAlarm = Alarm.Builder.create(this, "ConfigRollbackAlarm")
                .alarmName("ecs-fargate-appconfig-demo-config-health")
                .alarmDescription("Triggers an AppConfig auto-rollback if the service degrades during a config rollout")
                .metric(Metric.Builder.create()
                        .namespace("AWS/ECS")
                        .metricName("CPUUtilization")
                        .dimensionsMap(Map.of(
                                "ClusterName", "ecs-fargate-appconfig-demo",
                                "ServiceName", "appconfig-demo-service"))
                        .statistic("Average")
                        .period(Duration.minutes(1))
                        .build())
                .threshold(95)
                .evaluationPeriods(1)
                .comparisonOperator(ComparisonOperator.GREATER_THAN_THRESHOLD)
                .treatMissingData(TreatMissingData.NOT_BREACHING)
                .build();

        // Role AppConfig assumes to read the alarm state.
        Role alarmRole = Role.Builder.create(this, "AppConfigAlarmRole")
                .assumedBy(new ServicePrincipal("appconfig.amazonaws.com"))
                .inlinePolicies(Map.of("read-alarms", PolicyDocument.Builder.create()
                        .statements(List.of(PolicyStatement.Builder.create()
                                .actions(List.of("cloudwatch:DescribeAlarms"))
                                .resources(List.of("*"))
                                .build()))
                        .build()))
                .build();

        this.environment = CfnEnvironment.Builder.create(this, "Environment")
                .applicationId(application.getRef())
                .name("production")
                .monitors(List.of(CfnEnvironment.MonitorsProperty.builder()
                        .alarmArn(rollbackAlarm.getAlarmArn())
                        .alarmRoleArn(alarmRole.getRoleArn())
                        .build()))
                .build();

        this.configurationProfile = CfnConfigurationProfile.Builder.create(this, "Profile")
                .applicationId(application.getRef())
                .name("runtime-settings")
                .locationUri("hosted")
                .type("AWS.Freeform")
                .build();

        // Fast strategy — for the live demo (instant, no bake time).
        CfnDeploymentStrategy deploymentStrategy = CfnDeploymentStrategy.Builder.create(this, "AllAtOnce")
                .name("ecs-fargate-demo-all-at-once")
                .deploymentDurationInMinutes(0)
                .growthFactor(100)
                .finalBakeTimeInMinutes(0)
                .replicateTo("NONE")
                .build();

        // Production-grade strategy — staged rollout with a bake window during which the
        // rollback alarm is watched. 50% of targets first, then the rest over 2 minutes,
        // then a 1-minute bake before the config is considered good.
        CfnDeploymentStrategy gradualStrategy = CfnDeploymentStrategy.Builder.create(this, "Gradual")
                .name("ecs-fargate-demo-gradual")
                .description("Staged 50% linear rollout with a bake window for auto-rollback")
                .deploymentDurationInMinutes(2)
                .growthFactor(50)
                .growthType("LINEAR")
                .finalBakeTimeInMinutes(1)
                .replicateTo("NONE")
                .build();
        CfnOutput.Builder.create(this, "GradualStrategyId").value(gradualStrategy.getRef()).build();

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
