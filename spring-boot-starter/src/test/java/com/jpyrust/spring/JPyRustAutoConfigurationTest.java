package com.jpyrust.spring;

import static org.assertj.core.api.Assertions.assertThat;

import com.jpyrust.JPyRustBridge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class JPyRustAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(JPyRustAutoConfiguration.class));

    @Test
    void backsOffWhenTheApplicationDefinesItsOwnBridge() {
        runner.withBean(JPyRustBridge.class, () -> new JPyRustBridge("user-defined")).run(context -> {
            assertThat(context).hasSingleBean(JPyRustBridge.class);
            assertThat(context).hasSingleBean(JPyRustTemplate.class);
            assertThat(context.getBean("jpyRustHealthIndicator", HealthIndicator.class).health().getStatus())
                    .isEqualTo(Status.DOWN);
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "JPYRUST_PROVISION_TEST", matches = "true")
    void startsWorkersFromPropertiesAndRecordsMetrics(@TempDir(cleanup = CleanupMode.NEVER) Path dir) throws Exception {
        Path tasks = Files.createDirectories(dir.resolve("tasks"));
        Files.writeString(tasks.resolve("echo_tasks.py"),
                "from jpyrust import task\n\n\n@task(\"ECHO\")\ndef echo(data, args):\n    return data\n");
        Path requirements = Files.writeString(dir.resolve("requirements.txt"), "");

        runner.withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "jpyrust.instance-id=spring-test",
                        "jpyrust.work-dir=" + dir.resolve("work"),
                        "jpyrust.requirements=" + requirements,
                        "jpyrust.module-dirs=" + tasks,
                        "jpyrust.workers=2",
                        "jpyrust.task-timeout=30s")
                .run(context -> {
                    JPyRustTemplate template = context.getBean(JPyRustTemplate.class);
                    assertThat(template.call("ECHO", "hello")).isEqualTo("hello");
                    assertThat(context.getBean(MeterRegistry.class).get("jpyrust.calls")
                            .tag("task", "ECHO").tag("outcome", "success").timer().count()).isEqualTo(1);
                    assertThat(context.getBean("jpyRustHealthIndicator", HealthIndicator.class).health().getStatus())
                            .isEqualTo(Status.UP);
                });
    }
}
