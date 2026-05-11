package com.ga.pixgen.service.generation;

import com.ga.pixgen.config.AppProperties;
import com.ga.pixgen.config.GenerationModelsProperties;
import com.ga.pixgen.config.InternalServiceProperties;
import com.ga.pixgen.exception.UnknownGenerationModelException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenerationModelCatalogTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void listOptions_usesLocalCatalog_whenSimulationEnabled() {
        GenerationModelCatalog catalog = new GenerationModelCatalog(localModels());

        assertThat(catalog.listOptions())
                .extracting("modelId")
                .containsExactly("runwayml/stable-diffusion-v1-5");
    }

    @Test
    void listOptions_returnsInstalledCheckpointModels_whenInternalServiceModeEnabled() throws Exception {
        installModelListHandler("""
                {
                  "models": {
                    "checkpoint": [
                      {"type":"checkpoint","filename":"sd_xl_base_1.0.safetensors","size_bytes":100,"family":"sdxl"},
                      {"type":"checkpoint","filename":"flux1-schnell-fp8.safetensors","size_bytes":200,"family":"flux_schnell_single"}
                    ],
                    "lora": [
                      {"type":"lora","filename":"style.safetensors","size_bytes":10}
                    ]
                  },
                  "active_model": {"checkpoint":"sd_xl_base_1.0.safetensors","family":"sdxl"}
                }
                """);

        GenerationModelCatalog catalog = internalCatalog();

        assertThat(catalog.listOptions())
                .extracting("modelId")
                .containsExactly("sd_xl_base_1.0.safetensors", "flux1-schnell-fp8.safetensors");
        assertThat(catalog.listOptions())
                .extracting("label")
                .containsExactly("sd_xl_base_1.0.safetensors", "flux1-schnell-fp8.safetensors");
    }

    @Test
    void requireKnownModelId_acceptsInstalledCheckpoint_whenInternalServiceModeEnabled() throws Exception {
        installModelListHandler("""
                {
                  "models": {
                    "checkpoint": [
                      {"type":"checkpoint","filename":"sd_xl_base_1.0.safetensors","size_bytes":100}
                    ]
                  }
                }
                """);

        GenerationModelCatalog catalog = internalCatalog();

        catalog.requireKnownModelId("sd_xl_base_1.0.safetensors");
    }

    @Test
    void requireKnownModelId_rejectsNonCheckpointModel_whenInternalServiceModeEnabled() throws Exception {
        installModelListHandler("""
                {
                  "models": {
                    "lora": [
                      {"type":"lora","filename":"style.safetensors","size_bytes":10}
                    ]
                  }
                }
                """);

        GenerationModelCatalog catalog = internalCatalog();

        assertThatThrownBy(() -> catalog.requireKnownModelId("style.safetensors"))
                .isInstanceOf(UnknownGenerationModelException.class);
    }

    private void installModelListHandler(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/models", exchange -> sendJson(exchange, body));
        server.start();
    }

    private GenerationModelCatalog internalCatalog() {
        AppProperties appProperties = new AppProperties();
        appProperties.setInternalServiceSimulation(false);

        InternalServiceProperties internalProperties = new InternalServiceProperties();
        internalProperties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        internalProperties.setConnectTimeout(Duration.ofSeconds(1));
        internalProperties.setRequestTimeout(Duration.ofSeconds(2));

        return new GenerationModelCatalog(
                localModels(),
                appProperties,
                internalProperties,
                new ObjectMapper(),
                HttpClient.newHttpClient());
    }

    private static GenerationModelsProperties localModels() {
        GenerationModelsProperties properties = new GenerationModelsProperties();
        GenerationModelsProperties.ModelEntry entry = new GenerationModelsProperties.ModelEntry();
        entry.setModelId("runwayml/stable-diffusion-v1-5");
        entry.setLabel("Stable Diffusion v1.5");
        properties.setModels(List.of(entry));
        return properties;
    }

    private static void sendJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
