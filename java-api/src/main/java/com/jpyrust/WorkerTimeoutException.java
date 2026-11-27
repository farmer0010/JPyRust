package com.jpyrust;

public class WorkerTimeoutException extends JPyRustException {

    public WorkerTimeoutException(String message) {
        super(message);
    }

    public WorkerTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
