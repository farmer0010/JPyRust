package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import java.util.concurrent.CompletableFuture;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @Bean(destroyMethod = "close")
    public JPyRustBridge jpyRustBridge(@Value("${app.ai.work-dir}") String workDir,
            @Value("${app.ai.model-path:yolov8n.pt}") String modelPath,
            @Value("${app.ai.confidence:0.5}") float confidence) {
        JPyRustBridge bridge = new JPyRustBridge("demo-web");
        System.out.println("Scheduling JPyRust initialization (non-blocking)...");
        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("[Async] Starting JPyRust initialization...");
                bridge.initialize(workDir, modelPath, confidence);
                System.out.println("[Async] JPyRust initialization complete!");
            } catch (Exception e) {
                System.err.println("[Async] JPyRust initialization failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
        return bridge;
    }
}
