package com.ga.pixgen.service.generation;

import com.ga.pixgen.config.AppProperties;
import com.ga.pixgen.config.GenerationModelsProperties;
import com.ga.pixgen.config.InternalServiceProperties;
import com.ga.pixgen.dto.GenerationModelOptionResponse;
import com.ga.pixgen.exception.UnknownGenerationModelException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    private static final String CHECKPOINT_MODEL_TYPE = "checkpoint";

    private final AppProperties appProperties;
    private final InternalServiceProperties internalServiceProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final List<GenerationModelOptionResponse> localOptions;
    private final Map<String, String> localIdByNormalized;

    @Autowired
    public GenerationModelCatalog(GenerationModelsProperties properties,
                                  AppProperties appProperties,
                                  InternalServiceProperties internalServiceProperties,
                                  ObjectMapper objectMapper) {
        this(properties, appProperties, internalServiceProperties, objectMapper, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(internalServiceProperties.getConnectTimeout())
                .build());
    }

    /**
     * Test-friendly constructor for the local catalog path.
     *
     * @param properties the properties value
     */
    public GenerationModelCatalog(GenerationModelsProperties properties) {
        this(properties, new AppProperties(), new InternalServiceProperties(), new ObjectMapper(), HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build());
    }

    GenerationModelCatalog(GenerationModelsProperties properties,
                           AppProperties appProperties,
                           InternalServiceProperties internalServiceProperties,
                           ObjectMapper objectMapper,
                           HttpClient httpClient) {
        this.appProperties = appProperties;
        this.internalServiceProperties = internalServiceProperties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        Map<String, String> built = new LinkedHashMap<>();
        for (GenerationModelsProperties.ModelEntry e : properties.getModels()) {
            if (e.getModelId() == null || e.getModelId().isBlank()) {
                continue;
            }
            String id = e.getModelId().trim();
            built.put(normalize(id), id);
        }
        this.localIdByNormalized = Map.copyOf(built);
        this.localOptions = localIdByNormalized.values().stream()
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
        if (appProperties.isInternalServiceSimulation()) {
            return localOptions;
        }
        return listInternalCheckpointOptions();
    }

    /**
     * @param modelId the model id value
     */
    public void requireKnownModelId(String modelId) {
        if (modelId == null || modelId.isBlank()) {
            throw new UnknownGenerationModelException(modelId);
        }
        Map<String, String> idByNormalized = appProperties.isInternalServiceSimulation()
                ? localIdByNormalized
                : buildLookup(listInternalCheckpointOptions());
        if (!idByNormalized.containsKey(normalize(modelId))) {
            throw new UnknownGenerationModelException(modelId);
        }
    }

    private List<GenerationModelOptionResponse> listInternalCheckpointOptions() {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/v1/models"))
                .timeout(internalServiceProperties.getRequestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to fetch internal service models", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while fetching internal service models", e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException(
                    "Internal service returned HTTP " + response.statusCode() + " while listing models");
        }
        JsonNode checkpoints = readJson(response.body())
                .path("models")
                .path(CHECKPOINT_MODEL_TYPE);
        if (!checkpoints.isArray()) {
            return List.of();
        }
        Map<String, GenerationModelOptionResponse> options = new LinkedHashMap<>();
        for (JsonNode checkpoint : checkpoints) {
            String filename = text(checkpoint, "filename");
            if (filename == null || filename.isBlank()) {
                continue;
            }
            String trimmed = filename.trim();
            options.put(normalize(trimmed), new GenerationModelOptionResponse(trimmed, trimmed));
        }
        return List.copyOf(options.values());
    }

    private JsonNode readJson(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Failed to decode internal service model list: " + e.getMessage(), e);
        }
    }

    private URI uri(String path) {
        return URI.create(normalizeBaseUrl(internalServiceProperties.getBaseUrl()) + path);
    }

    private static Map<String, String> buildLookup(List<GenerationModelOptionResponse> options) {
        Map<String, String> built = new LinkedHashMap<>();
        for (GenerationModelOptionResponse option : options) {
            if (option.modelId() != null && !option.modelId().isBlank()) {
                String id = option.modelId().trim();
                built.put(normalize(id), id);
            }
        }
        return built;
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static String normalizeBaseUrl(String configuredBaseUrl) {
        if (configuredBaseUrl == null || configuredBaseUrl.isBlank()) {
            throw new IllegalArgumentException("app.internal-service.base-url must not be blank");
        }
        String trimmed = configuredBaseUrl.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String normalize(String modelId) {
        return modelId.trim().toLowerCase(Locale.ROOT);
    }
}
