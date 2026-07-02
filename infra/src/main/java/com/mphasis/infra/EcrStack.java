package com.mphasis.infra;

import java.util.List;
import software.amazon.awscdk.CfnOutput;
import software.amazon.awscdk.Stack;
import software.amazon.awscdk.StackProps;
import software.amazon.awscdk.services.ecr.IRepository;
import software.amazon.awscdk.services.ecr.LifecycleRule;
import software.amazon.awscdk.services.ecr.Repository;
import software.constructs.Construct;

public class EcrStack extends Stack {

    private final IRepository repository;

    public EcrStack(final Construct scope, final String id) {
        super(scope, id, StackProps.builder()
                .description("ECR repository for the ECS Fargate AppConfig demo application")
                .build());

        this.repository = Repository.Builder.create(this, "AppRepository")
                .repositoryName("ecs-fargate-appconfig-demo")
                .imageScanOnPush(true)
                .lifecycleRules(List.of(LifecycleRule.builder()
                        .description("Keep only the ten most recent images")
                        .maxImageCount(10)
                        .build()))
                .build();

        CfnOutput.Builder.create(this, "RepositoryUri")
                .value(repository.getRepositoryUri())
                .description("Push application images here")
                .build();
    }

    public IRepository getRepository() {
        return repository;
    }
}
