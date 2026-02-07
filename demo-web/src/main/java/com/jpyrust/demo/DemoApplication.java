package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import jakarta.annotation.PostConstruct;
import java.util.concurrent.CompletableFuture;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @PostConstruct
    public void init() {
        System.out.println("Scheduling JPyRust initialization (non-blocking)...");
        // Run Python initialization in background thread so Tomcat can start
        // immediately
        CompletableFuture.runAsync(() -> {
            try {
                System.out.println("[Async] Starting JPyRust initialization...");
                JPyRustBridge.initialize();
                System.out.println("[Async] JPyRust initialization complete!");
            } catch (Exception e) {
                System.err.println("[Async] JPyRust initialization failed: " + e.getMessage());
                e.printStackTrace();
            }
        });
    }
}
