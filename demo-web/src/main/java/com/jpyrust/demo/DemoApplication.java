package com.jpyrust.demo;

import com.jpyrust.JPyRustBridge;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import jakarta.annotation.PostConstruct;

@SpringBootApplication
public class DemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(DemoApplication.class, args);
    }

    @PostConstruct
    public void init() {
        // Initialize JPyRust Library on Startup
        System.out.println("Initializing JPyRust...");
        JPyRustBridge.initialize();
    }
}
