package com.ga.pixgen.exception;

/**
 * Thrown when a user does not have enough credits to cover a job's cost,
 * either at submission time or when the worker performs its conditional
 * credit deduction. Maps to HTTP 402 Payment Required via
 * {@code GlobalExceptionHandler}.
 */
public class InsufficientCreditsException extends RuntimeException {

    public InsufficientCreditsException(int required, int available) {
        super("Insufficient credits: required " + required + " but only " + available + " available");
    }

    public InsufficientCreditsException(String message) {
        super(message);
    }
}
