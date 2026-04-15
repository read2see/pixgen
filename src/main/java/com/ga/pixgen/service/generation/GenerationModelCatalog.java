package com.ga.pixgen.service.generation;

import com.ga.pixgen.config.GenerationModelsProperties;
import com.ga.pixgen.dto.GenerationModelOptionResponse;
import com.ga.pixgen.exception.UnknownGenerationModelException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates {@code MODEL_ID} values against configured local models and
 * exposes the catalog for UI pickers.
 */
@Component
public class GenerationModelCatalog {

    private final List<GenerationModelOptionResponse> options;
    private final Map<String, String> idByNormalized;

    public GenerationModelCatalog(GenerationModelsProperties properties) {
        Map<String, String> built = new LinkedHashMap<>();
        for (GenerationModelsProperties.ModelEntry e : properties.getModels()) {
            if (e.getModelId() == null || e.getModelId().isBlank()) {
                continue;
            }
            String id = e.getModelId().trim();
            built.put(normalize(id), id);
        }
        this.idByNormalized = Map.copyOf(built);
        this.options = idByNormalized.values().stream()
                .map(id -> new GenerationModelOptionResponse(
                        id,
                        labelFor(id, properties)))
                .toList();
    }

    private static String labelFor(String id, GenerationModelsProperties properties) {
        for (GenerationModelsProperties.ModelEntry e : properties.getModels()) {
            if (e.getModelId() != null && id.equals(e.getModelId().trim())) {
                return e.getLabel() != null && !e.getLabel().isBlank() ? e.getLabel().trim() : id;
            }
        }
        return id;
    }

    public List<GenerationModelOptionResponse> listOptions() {
        return options;
    }

    /**
     * @param modelId the model id value
     */
    public void requireKnownModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new UnknownGenerationModelException(modelId);
        }
        if (!idByNormalized.containsKey(normalize(modelId))) {
            throw new UnknownGenerationModelException(modelId);
        }
    }

    private static String normalize(String modelId) {
        return modelId.trim().toLowerCase(Locale.ROOT);
    }
}
