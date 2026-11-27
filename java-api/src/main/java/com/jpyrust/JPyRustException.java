package com.jpyrust;

public class JPyRustException extends RuntimeException {

    public JPyRustException(String message) {
        super(message);
    }

    public JPyRustException(String message, Throwable cause) {
        super(message, cause);
    }
}
