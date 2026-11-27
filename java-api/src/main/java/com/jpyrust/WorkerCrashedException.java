package com.jpyrust;

public class WorkerCrashedException extends JPyRustException {

    public WorkerCrashedException(String message) {
        super(message);
    }

    public WorkerCrashedException(String message, Throwable cause) {
        super(message, cause);
    }
}
