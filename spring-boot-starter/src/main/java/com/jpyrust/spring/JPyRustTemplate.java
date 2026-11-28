package com.jpyrust.spring;

import com.jpyrust.JPyRustBridge;
import com.jpyrust.NDArray;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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

    public Stream<byte[]> callStream(String task, byte[] input, String args) {
        long started = System.nanoTime();
        Stream<byte[]> chunks;
        try {
            chunks = bridge.callStream(task, input, args);
        } catch (RuntimeException e) {
            observer.completed(task, System.nanoTime() - started, e);
            throw e;
        }
        Iterator<byte[]> source = chunks.iterator();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean recorded = new AtomicBoolean();
        Iterator<byte[]> observed = new Iterator<>() {
            @Override
            public boolean hasNext() {
                try {
                    return source.hasNext();
                } catch (RuntimeException e) {
                    failure.set(e);
                    throw e;
                }
            }

            @Override
            public byte[] next() {
                try {
                    return source.next();
                } catch (RuntimeException e) {
                    failure.set(e);
                    throw e;
                }
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(observed, Spliterator.ORDERED | Spliterator.NONNULL),
                false).onClose(() -> {
                    try {
                        chunks.close();
                    } finally {
                        if (recorded.compareAndSet(false, true)) {
                            observer.completed(task, System.nanoTime() - started, failure.get());
                        }
                    }
                });
    }

    public Stream<String> callStream(String task, String input) {
        return callStream(task, input.getBytes(StandardCharsets.UTF_8), "")
                .map(chunk -> new String(chunk, StandardCharsets.UTF_8));
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
