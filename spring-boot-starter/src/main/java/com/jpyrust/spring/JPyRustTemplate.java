package com.jpyrust.spring;

import com.jpyrust.JPyRustBridge;
import com.jpyrust.NDArray;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

public class JPyRustTemplate {

    private final JPyRustBridge bridge;
    private final CallObserver observer;

    public JPyRustTemplate(JPyRustBridge bridge, CallObserver observer) {
        this.bridge = bridge;
        this.observer = observer;
    }

    public byte[] call(String task, byte[] input, String args) {
        return observe(task, () -> bridge.call(task, input, args));
    }

    public NDArray call(String task, NDArray input, String args) {
        return observe(task, () -> bridge.call(task, input, args));
    }

    public String call(String task, String input) {
        return new String(call(task, input.getBytes(StandardCharsets.UTF_8), ""), StandardCharsets.UTF_8);
    }

    public CompletableFuture<byte[]> callAsync(String task, byte[] input, String args) {
        long started = System.nanoTime();
        return bridge.callAsync(task, input, args)
                .whenComplete((result, error) -> observer.completed(task, System.nanoTime() - started,
                        error instanceof java.util.concurrent.CompletionException ? error.getCause() : error));
    }

    public JPyRustBridge bridge() {
        return bridge;
    }

    private <T> T observe(String task, Supplier<T> call) {
        long started = System.nanoTime();
        try {
            T result = call.get();
            observer.completed(task, System.nanoTime() - started, null);
            return result;
        } catch (RuntimeException e) {
            observer.completed(task, System.nanoTime() - started, e);
            throw e;
        }
    }
}
