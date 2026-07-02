package com.mphasis.infra;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.util.Map;
import org.junit.jupiter.api.Test;
import software.amazon.awscdk.App;
import software.amazon.awscdk.AppProps;
import software.amazon.awscdk.assertions.Template;

class InfraAppTest {

    @Test
    void createsAllThreeStacks() {
        App app = new App();

        InfraApp.createStacks(app);

        assertEquals(3, app.getNode().getChildren().size());
        assertNotNull(app.getNode().tryFindChild("DemoEcrStack"));
        assertNotNull(app.getNode().tryFindChild("DemoAppConfigStack"));
        assertNotNull(app.getNode().tryFindChild("DemoFargateServiceStack"));
    }

    @Test
    void usesLatestTagWhenNoImageTagContextIsProvided() {
        App app = new App();

        InfraApp.createStacks(app);

        Template template = Template.fromStack(
                (software.amazon.awscdk.Stack) app.getNode().findChild("DemoFargateServiceStack"));
        String rendered = template.toJSON().toString();
        assertTrue(rendered.contains(":latest"));
    }

    @Test
    void usesImageTagFromContext() {
        App app = new App(AppProps.builder()
                .context(Map.of("imageTag", "build-42"))
                .build());

        InfraApp.createStacks(app);

        Template template = Template.fromStack(
                (software.amazon.awscdk.Stack) app.getNode().findChild("DemoFargateServiceStack"));
        String rendered = template.toJSON().toString();
        assertTrue(rendered.contains(":build-42"));
    }

    @Test
    void mainSynthesizesWithoutError() {
        assertDoesNotThrow(() -> InfraApp.main(new String[0]));
    }

    @Test
    void utilityConstructorIsPrivateButInvocable() throws Exception {
        Constructor<InfraApp> constructor = InfraApp.class.getDeclaredConstructor();
        assertTrue(Modifier.isPrivate(constructor.getModifiers()));
        constructor.setAccessible(true);
        assertNotNull(constructor.newInstance());
    }
}
