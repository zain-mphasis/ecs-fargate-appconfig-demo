package com.mphasis.infra;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.assertions.Template;

class EcrStackTest {

    @Test
    void definesScannedRepositoryWithLifecycleRule() {
        EcrStack stack = new EcrStack(new App(), "TestEcrStack");
        Template template = Template.fromStack(stack);

        template.resourceCountIs("AWS::ECR::Repository", 1);
        template.hasResourceProperties("AWS::ECR::Repository", Map.of(
                "RepositoryName", "ecs-fargate-appconfig-demo",
                "ImageScanningConfiguration", Map.of("ScanOnPush", true)));

        assertNotNull(stack.getRepository());
    }
}
