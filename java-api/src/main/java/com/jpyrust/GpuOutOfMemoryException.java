package com.jpyrust;

public class GpuOutOfMemoryException extends TaskFailedException {

    public GpuOutOfMemoryException(String message) {
        super(message);
    }

    public GpuOutOfMemoryException(String message, Throwable cause) {
        super(message, cause);
    }
}
