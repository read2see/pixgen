package com.ga.pixgen.service.images;

import com.ga.pixgen.config.ImagesProperties;
import com.ga.pixgen.config.InternalServiceProperties;
import com.ga.pixgen.repository.JobRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import tools.jackson.databind.ObjectMapper;

class InternalServiceImageGenerationStrategyTest {

    private static final byte[] PNG_BYTES = "png-bytes".getBytes(StandardCharsets.UTF_8);

    @TempDir
    private Path imageRoot;

    private HttpServer server;
    private InternalServiceProperties properties;
    private JobRepository jobRepository;
    private InternalServiceImageGenerationStrategy strategy;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();

        ImagesProperties imagesProperties = new ImagesProperties();
        imagesProperties.setLocalDir(imageRoot.toString());

        properties = new InternalServiceProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofSeconds(1));
        properties.setRequestTimeout(Duration.ofSeconds(2));
        properties.setPollInterval(Duration.ofMillis(1));
        jobRepository = mock(JobRepository.class);

        strategy = new InternalServiceImageGenerationStrategy(
                properties,
                new LocalImageStorage(imagesProperties),
                new ObjectMapper(),
                jobRepository,
                HttpClient.newHttpClient());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
        Thread.interrupted();
    }

    @Test
    void generate_prefersSseEvents_andDownloadsCompletedImage() throws Exception {
        AtomicInteger activeRequests = new AtomicInteger();
        AtomicInteger pollingRequests = new AtomicInteger();
        installActiveModelHandler(activeRequests);
        installGenerateHandler(202, "{\"job_id\":\"job-1\",\"status\":\"queued\"}");
        server.createContext("/api/v1/jobs/job-1/events", exchange -> sendSse(exchange,
                """
                event: queued
                data: {"job_id":"job-1","status":"queued","progress":0.0,"image_ready":false,"width":32,"height":16}

                event: running
                data: {"job_id":"job-1","status":"running","progress":0.5,"image_ready":false,"width":32,"height":16}

                event: completed
                data: {"job_id":"job-1","status":"completed","progress":1.0,"image_ready":true,"width":32,"height":16}

                event: end
                data: {}

                """));
        server.createContext("/api/v1/jobs/job-1", exchange -> {
            pollingRequests.incrementAndGet();
            sendJson(exchange, 200, "{}");
        });
        server.createContext("/api/v1/jobs/job-1/image", exchange -> sendBytes(exchange, 200, PNG_BYTES));

        List<Integer> progress = new ArrayList<>();
        StoredImage stored = strategy.generate(sampleRequest(), progress::add);

        assertThat(progress).containsExactly(0, 50, 100);
        assertThat(stored.width()).isEqualTo(32);
        assertThat(stored.height()).isEqualTo(16);
        assertThat(Files.readAllBytes(imageRoot.resolve(stored.relativePath()))).isEqualTo(PNG_BYTES);
        assertThat(activeRequests).hasValue(1);
        assertThat(pollingRequests).hasValue(0);
        verify(jobRepository).updateInternalServiceJobId(101L, "job-1");
    }

    @Test
    void generate_fallsBackToPolling_whenSseCannotBeOpened() throws Exception {
        installActiveModelHandler(new AtomicInteger());
        AtomicInteger jobPolls = new AtomicInteger();
        installGenerateHandler(202, "{\"job_id\":\"job-2\",\"status\":\"queued\"}");
        server.createContext("/api/v1/jobs/job-2/events", exchange -> sendJson(exchange, 404, "{\"detail\":\"missing\"}"));
        server.createContext("/api/v1/jobs/job-2", exchange -> {
            int request = jobPolls.incrementAndGet();
            if (request == 1) {
                sendJson(exchange, 200,
                        "{\"job_id\":\"job-2\",\"status\":\"running\",\"progress\":0.25,\"image_ready\":false}");
            } else {
                sendJson(exchange, 200,
                        "{\"job_id\":\"job-2\",\"status\":\"completed\",\"progress\":1.0,\"image_ready\":true}");
            }
        });
        server.createContext("/api/v1/jobs/job-2/image", exchange -> sendBytes(exchange, 200, PNG_BYTES));

        List<Integer> progress = new ArrayList<>();
        StoredImage stored = strategy.generate(sampleRequest(), progress::add);

        assertThat(progress).containsExactly(25, 100);
        assertThat(stored.sizeBytes()).isEqualTo(PNG_BYTES.length);
        assertThat(jobPolls).hasValue(2);
    }

    @Test
    void generate_throwsRuntimeException_whenSseReportsFailure() {
        installActiveModelHandler(new AtomicInteger());
        installGenerateHandler(202, "{\"job_id\":\"job-3\",\"status\":\"queued\"}");
        server.createContext("/api/v1/jobs/job-3/events", exchange -> sendSse(exchange,
                """
                event: failed
                data: {"job_id":"job-3","status":"failed","progress":0.4,"error":"model exploded","image_ready":false}

                """));

        assertThatThrownBy(() -> strategy.generate(sampleRequest(), ignored -> {
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("model exploded");
    }

    @Test
    void generate_throwsInterruptedException_whenSseReportsCancelled() {
        installActiveModelHandler(new AtomicInteger());
        installGenerateHandler(202, "{\"job_id\":\"job-4\",\"status\":\"queued\"}");
        server.createContext("/api/v1/jobs/job-4/events", exchange -> sendSse(exchange,
                """
                event: cancelled
                data: {"job_id":"job-4","status":"cancelled","progress":0.1,"image_ready":false}

                """));

        assertThatThrownBy(() -> strategy.generate(sampleRequest(), ignored -> {
        }))
                .isInstanceOf(InterruptedException.class)
                .hasMessageContaining("cancelled");
    }

    @Test
    void generate_throwsClearFailure_whenQueueIsFull() {
        installActiveModelHandler(new AtomicInteger());
        installGenerateHandler(503, "{\"detail\":\"queue full\"}");

        assertThatThrownBy(() -> strategy.generate(sampleRequest(), ignored -> {
        }))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("queue is full");
    }

    @Test
    void generate_cancelsInternalJob_whenLocalInterruptIsObservedDuringPollingFallback() {
        installActiveModelHandler(new AtomicInteger());
        AtomicInteger cancelRequests = new AtomicInteger();
        installGenerateHandler(202, "{\"job_id\":\"job-5\",\"status\":\"queued\"}");
        server.createContext("/api/v1/jobs/job-5/events", exchange -> sendJson(exchange, 404, "{\"detail\":\"missing\"}"));
        server.createContext("/api/v1/jobs/job-5", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                cancelRequests.incrementAndGet();
                sendJson(exchange, 200, "{\"job_id\":\"job-5\",\"status\":\"cancelled\"}");
                return;
            }
            sendJson(exchange, 200,
                    "{\"job_id\":\"job-5\",\"status\":\"running\",\"progress\":0.25,\"image_ready\":false}");
        });

        assertThatThrownBy(() -> strategy.generate(sampleRequest(), ignored -> Thread.currentThread().interrupt()))
                .isInstanceOf(InterruptedException.class);
        assertThat(cancelRequests).hasValue(1);
    }

    @Test
    void generate_activatesRequestedModel_beforeSubmittingGenerationJob() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        AtomicInteger activeSequence = new AtomicInteger();
        AtomicInteger generateSequence = new AtomicInteger();
        server.createContext("/api/v1/models/active", exchange -> {
            activeSequence.set(sequence.incrementAndGet());
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(requestBody).contains("\"checkpoint\":\"model.ckpt\"");
            sendJson(exchange, 200, """
                    {"active_model":{"checkpoint":"model.ckpt","family":"sdxl"}}
                    """);
        });
        server.createContext("/api/v1/generate", exchange -> {
            generateSequence.set(sequence.incrementAndGet());
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(requestBody).doesNotContain("\"model\"");
            sendJson(exchange, 202, "{\"job_id\":\"job-6\",\"status\":\"queued\"}");
        });
        server.createContext("/api/v1/jobs/job-6/events", exchange -> sendSse(exchange,
                """
                event: completed
                data: {"job_id":"job-6","status":"completed","progress":1.0,"image_ready":true}

                """));
        server.createContext("/api/v1/jobs/job-6/image", exchange -> sendBytes(exchange, 200, PNG_BYTES));

        strategy.generate(sampleRequest(), ignored -> {
        });

        assertThat(activeSequence).hasValue(1);
        assertThat(generateSequence).hasValue(2);
    }

    private void installActiveModelHandler(AtomicInteger activeRequests) {
        server.createContext("/api/v1/models/active", exchange -> {
            activeRequests.incrementAndGet();
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(requestBody).contains("\"checkpoint\":\"model.ckpt\"");
            sendJson(exchange, 200, """
                    {"active_model":{"checkpoint":"model.ckpt","family":"sdxl"}}
                    """);
        });
    }

    private void installGenerateHandler(int status, String body) {
        server.createContext("/api/v1/generate", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(requestBody).contains("\"prompt\":\"a cat\"");
            assertThat(requestBody).contains("\"steps\":20");
            assertThat(requestBody).contains("\"cfg\":7.5");
            assertThat(requestBody).doesNotContain("\"model\"");
            sendJson(exchange, status, body);
        });
    }

    private static GenerationRequest sampleRequest() {
        return new GenerationRequest(
                101L,
                7L,
                64,
                64,
                "a cat",
                null,
                20,
                7.5,
                42L,
                "euler-a",
                "model.ckpt");
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        sendBytes(exchange, status, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendSse(HttpExchange exchange, String body) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
        sendBytes(exchange, 200, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendBytes(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
