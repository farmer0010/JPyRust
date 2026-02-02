package com.jpyrust;

import org.junit.jupiter.api.Test;

public class TestLoader {
    @Test
    public void testLoading() {
        System.out.println("Testing JPyRustBridge Loading...");
        try {
            // Force class loading to trigger static block
            Class.forName("com.jpyrust.JPyRustBridge");
            System.out.println("JPyRustBridge loaded successfully!");

            JPyRustBridge.initialize();
            System.out.println("JPyRustBridge initialized successfully!");

        } catch (Throwable e) {
            System.err.println("Test Failed:");
            try (java.io.PrintStream ps = new java.io.PrintStream("exception_stack.txt")) {
                e.printStackTrace(ps);
            } catch (java.io.FileNotFoundException ex) {
                ex.printStackTrace();
            }
            throw new RuntimeException(e);
        }
    }
}
