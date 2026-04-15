package com.ga.pixgen.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * One entry in the configured local model catalog for front-end select menus.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GenerationModelOptionResponse(
        String modelId,
        String label
) {
}
