package com.mphasis.infra;

import java.util.List;
import java.util.Map;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ec2.Vpc;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecs.Cluster;
import software.amazon.awscdk.services.ecs.ContainerImage;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedFargateService;
import software.amazon.awscdk.services.ecs.patterns.ApplicationLoadBalancedTaskImageOptions;
import software.amazon.awscdk.services.elasticloadbalancingv2.HealthCheck;
import software.amazon.awscdk.services.iam.PolicyStatement;
import software.constructs.Construct;

/**
 * VPC, ECS Fargate cluster and the load-balanced Spring Boot service. The container is
 * pointed at the AppConfig resources through environment variables and its task role is
 * granted permission to open AppConfig configuration sessions.
 */
public class FargateServiceStack extends Stack {

    public FargateServiceStack(final Construct scope,
                               final String id,
                               final IRepository repository,
                               final String imageTag,
                               final AppConfigStack appConfig) {
        super(scope, id, StackProps.builder()
                .description("ECS Fargate cluster and service for the AppConfig demo application")
                .build());

        Vpc vpc = Vpc.Builder.create(this, "Vpc")
                .maxAzs(2)
                .natGateways(1)
                .build();

        Cluster cluster = Cluster.Builder.create(this, "Cluster")
                .clusterName("ecs-fargate-appconfig-demo")
                .vpc(vpc)
                .containerInsights(true)
                .build();

        ApplicationLoadBalancedFargateService service =
                ApplicationLoadBalancedFargateService.Builder.create(this, "Service")
                        .serviceName("appconfig-demo-service")
                        .cluster(cluster)
                        .cpu(512)
                        .memoryLimitMiB(1024)
                        .desiredCount(2)
                        .publicLoadBalancer(true)
                        .taskImageOptions(ApplicationLoadBalancedTaskImageOptions.builder()
                                .image(ContainerImage.fromEcrRepository(repository, imageTag))
                                .containerPort(8080)
                                .environment(Map.of(
                                        "SPRING_PROFILES_ACTIVE", "aws",
                                        "AWS_REGION", getRegion(),
                                        "APPCONFIG_APPLICATION_ID", appConfig.getApplicationId(),
                                        "APPCONFIG_ENVIRONMENT_ID", appConfig.getEnvironmentId(),
                                        "APPCONFIG_PROFILE_ID", appConfig.getConfigurationProfileId(),
                                        "APPCONFIG_POLL_INTERVAL_SECONDS", "30"))
                                .build())
                        .build();

        service.getTargetGroup().configureHealthCheck(HealthCheck.builder()
                .path("/actuator/health")
                .healthyHttpCodes("200")
                .build());

        service.getTaskDefinition().getTaskRole().addToPrincipalPolicy(PolicyStatement.Builder.create()
                .actions(List.of(
                        "appconfig:StartConfigurationSession",
                        "appconfig:GetLatestConfiguration"))
                .resources(List.of("arn:aws:appconfig:" + getRegion() + ":" + getAccount()
                        + ":application/" + appConfig.getApplicationId()
                        + "/environment/" + appConfig.getEnvironmentId()
                        + "/configuration/" + appConfig.getConfigurationProfileId()))
                .build());
    }
}
