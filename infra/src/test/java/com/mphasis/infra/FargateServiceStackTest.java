package com.mphasis.infra;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Match;
import software.amazon.awscdk.assertions.Template;

class FargateServiceStackTest {

    private Template template;

    @BeforeEach
    void synthesize() {
        App app = new App();
        EcrStack ecrStack = new EcrStack(app, "TestEcrStack");
        AppConfigStack appConfigStack = new AppConfigStack(app, "TestAppConfigStack");
        FargateServiceStack stack = new FargateServiceStack(app, "TestFargateStack",
                ecrStack.getRepository(), "1.2.3", appConfigStack);
        template = Template.fromStack(stack);
    }

    @Test
    void definesVpcClusterAndLoadBalancedFargateService() {
        template.resourceCountIs("AWS::EC2::VPC", 1);
        template.resourceCountIs("AWS::ECS::Cluster", 1);
        template.resourceCountIs("AWS::ElasticLoadBalancingV2::LoadBalancer", 1);
        template.hasResourceProperties("AWS::ECS::Service", Map.of(
                "DesiredCount", 2,
                "LaunchType", "FARGATE"));
    }

    @Test
    void containerIsWiredToAppConfigViaEnvironmentVariables() {
        template.hasResourceProperties("AWS::ECS::TaskDefinition", Map.of(
                "ContainerDefinitions", Match.arrayWith(List.of(Match.objectLike(Map.of(
                        "PortMappings", Match.arrayWith(List.of(Match.objectLike(Map.of(
                                "ContainerPort", 8080)))),
                        "Environment", Match.arrayWith(List.of(
                                Match.objectLike(Map.of(
                                        "Name", "APPCONFIG_APPLICATION_ID")),
                                Match.objectLike(Map.of(
                                        "Name", "SPRING_PROFILES_ACTIVE",
                                        "Value", "aws"))))))))));
    }

    @Test
    void taskRoleCanReadFromAppConfig() {
        template.hasResourceProperties("AWS::IAM::Policy", Map.of(
                "PolicyDocument", Match.objectLike(Map.of(
                        "Statement", Match.arrayWith(List.of(Match.objectLike(Map.of(
                                "Action", Match.arrayWith(List.of(
                                        "appconfig:GetLatestConfiguration"))))))))));
    }

    @Test
    void healthCheckTargetsActuatorEndpoint() {
        template.hasResourceProperties("AWS::ElasticLoadBalancingV2::TargetGroup", Map.of(
                "HealthCheckPath", "/actuator/health",
                "Matcher", Map.of("HttpCode", "200")));
    }
}
