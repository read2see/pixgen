package com.ga.pixgen.exception;

public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resource, Object identifier) {
        super(resource + " with id " + identifier + " not found");
    }
}
