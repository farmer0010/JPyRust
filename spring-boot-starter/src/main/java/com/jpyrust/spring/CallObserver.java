package com.jpyrust.spring;

@FunctionalInterface
public interface CallObserver {

    CallObserver NONE = (task, nanos, error) -> {
    };

    void completed(String task, long nanos, Throwable error);
}
