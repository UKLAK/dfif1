package com.javastudio.security;

public class GasQuotaExceededException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public GasQuotaExceededException(String message) {
        super(message);
    }
}
