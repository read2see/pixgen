package com.ga.pixgen.exception;

public class ExpiredTokenException extends RuntimeException {

    public ExpiredTokenException(String message) {
        super(message);
    }
}
