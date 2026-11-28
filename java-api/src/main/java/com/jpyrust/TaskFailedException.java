package com.jpyrust;

public class TaskFailedException extends JPyRustException {

    public TaskFailedException(String message) {
        super(message);
    }

    public TaskFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
