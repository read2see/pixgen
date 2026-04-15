package com.ga.pixgen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Locally configured diffusion models ({@code model_id} strings as used by
 * docker-diffusers-api {@code MODEL_ID}), each with a display label for the UI.
 */
@ConfigurationProperties(prefix = "app.generation")
public class GenerationModelsProperties {

    private List<ModelEntry> models = new ArrayList<>();

    public List<ModelEntry> getModels() {
        return models;
    }

    public void setModels(List<ModelEntry> models) {
        this.models = models != null ? models : new ArrayList<>();
    }

    public static class ModelEntry {

        private String modelId;
        private String label;

        public String getModelId() {
            return modelId;
        }

        public void setModelId(String modelId) {
            this.modelId = modelId;
        }

        public String getLabel() {
            return label;
        }

        public void setLabel(String label) {
            this.label = label;
        }
    }
}
