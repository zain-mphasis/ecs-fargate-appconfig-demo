package com.mphasis.infra;

import software.amazon.awscdk.App;

/**
 * CDK application entry point. Synthesizes three stacks:
 * <ol>
 *   <li>{@link EcrStack} - the container registry the pipeline pushes images to</li>
 *   <li>{@link AppConfigStack} - AWS AppConfig application, environment, profile and initial version</li>
 *   <li>{@link FargateServiceStack} - VPC, ECS Fargate cluster and the load-balanced service</li>
 * </ol>
 * The image tag deployed by the service is supplied by the pipeline via {@code -c imageTag=<tag>}.
 */
public final class InfraApp {

    private InfraApp() {
    }

    public static void main(final String[] args) {
        App app = new App();
        createStacks(app);
        app.synth();
    }

    static void createStacks(final App app) {
        Object imageTagContext = app.getNode().tryGetContext("imageTag");
        String imageTag = imageTagContext == null ? "latest" : imageTagContext.toString();

        EcrStack ecrStack = new EcrStack(app, "DemoEcrStack");
        AppConfigStack appConfigStack = new AppConfigStack(app, "DemoAppConfigStack");
        new FargateServiceStack(app, "DemoFargateServiceStack",
                ecrStack.getRepository(), imageTag, appConfigStack);
    }
}
