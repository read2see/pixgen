package com.ga.pixgen.exception;

/**
 * Raised when a job references a {@code MODEL_ID} that is not in the configured
 * local model list.
 */
public class UnknownGenerationModelException extends RuntimeException {

    public UnknownGenerationModelException(String modelId) {
        super(messageFor(modelId));
    }

    private static String messageFor(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            return "model_id is required and must match a configured local model";
        }
        return "Unknown or unavailable model_id: " + modelId;
    }
}
