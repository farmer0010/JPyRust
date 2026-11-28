package com.jpyrust.spring;

import com.jpyrust.JPyRustBridge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@AutoConfiguration(afterName = {
        "org.springframework.boot.actuate.autoconfigure.metrics.CompositeMeterRegistryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.metrics.export.simple.SimpleMetricsExportAutoConfiguration"})
@EnableConfigurationProperties(JPyRustProperties.class)
public class JPyRustAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public JPyRustBridge jpyRustBridge(JPyRustProperties properties) {
        JPyRustBridge bridge = new JPyRustBridge(properties.getInstanceId())
                .workers(properties.getWorkers())
                .timeouts(properties.getStartupTimeout(), properties.getTaskTimeout())
                .model(properties.getModel(), properties.getConfidence())
                .whisperModel(properties.getWhisperModel())
                .extras(properties.getExtras().toArray(String[]::new));
        if (properties.getRequirements() != null) {
            bridge.requirements(properties.getRequirements());
        }
        if (properties.getPython() != null) {
            bridge.python(properties.getPython());
        }
        properties.getModuleDirs().forEach(bridge::moduleDir);
        if (properties.getWorkDir() != null) {
            bridge.initialize(properties.getWorkDir());
        } else {
            bridge.initialize();
        }
        return bridge;
    }

    @Bean
    @ConditionalOnMissingBean
    public JPyRustTemplate jpyRustTemplate(JPyRustBridge bridge, ObjectProvider<CallObserver> observer) {
        return new JPyRustTemplate(bridge, observer.getIfAvailable(() -> CallObserver.NONE));
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    @ConditionalOnBean(MeterRegistry.class)
    static class MetricsConfiguration {

        @Bean
        @ConditionalOnMissingBean
        CallObserver jpyRustMicrometerObserver(MeterRegistry registry) {
            return (task, nanos, error) -> Timer.builder("jpyrust.calls")
                    .tag("task", task.toUpperCase())
                    .tag("outcome", error == null ? "success" : error.getClass().getSimpleName())
                    .register(registry)
                    .record(nanos, TimeUnit.NANOSECONDS);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(HealthIndicator.class)
    static class HealthConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "jpyRustHealthIndicator")
        HealthIndicator jpyRustHealthIndicator(JPyRustBridge bridge) {
            return () -> {
                try {
                    return Health.up().withDetail("status", bridge.sendTask("STATUS", "NONE")).build();
                } catch (RuntimeException e) {
                    return Health.down(e).build();
                }
            };
        }
    }
}
