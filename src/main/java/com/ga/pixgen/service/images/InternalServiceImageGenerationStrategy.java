package com.ga.pixgen.service.images;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.ga.pixgen.config.InternalServiceProperties;
import com.ga.pixgen.repository.JobRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import java.util.function.IntConsumer;

/**
 * Image generation strategy backed by the internal service API.
 */
@Component
public class InternalServiceImageGenerationStrategy implements ImageGenerationStrategy {

    private static final String STATUS_QUEUED = "queued";
    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_CANCELLED = "cancelled";

    private final InternalServiceProperties properties;
    private final LocalImageStorage storage;
    private final ObjectMapper objectMapper;
    private final JobRepository jobRepository;
    private final HttpClient httpClient;
    private final String baseUrl;

    @Autowired
    public InternalServiceImageGenerationStrategy(InternalServiceProperties properties,
                                                  LocalImageStorage storage,
                                                  ObjectMapper objectMapper,
                                                  JobRepository jobRepository) {
        this(properties, storage, objectMapper, jobRepository, HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .build());
    }

    InternalServiceImageGenerationStrategy(InternalServiceProperties properties,
                                           LocalImageStorage storage,
                                           ObjectMapper objectMapper,
                                           JobRepository jobRepository,
                                           HttpClient httpClient) {
        this.properties = properties;
        this.storage = storage;
        this.objectMapper = objectMapper;
        this.jobRepository = jobRepository;
        this.httpClient = httpClient;
        this.baseUrl = normalizeBaseUrl(properties.getBaseUrl());
    }

    @Override
    public StoredImage generate(GenerationRequest request, IntConsumer progressListener)
            throws InterruptedException {
        String internalJobId = null;
        try {
            activateModel(request);
            internalJobId = submit(request);
            recordInternalJobId(request, internalJobId);
            JobSnapshot snapshot = waitForCompletion(internalJobId, progressListener);
            byte[] png = downloadImage(internalJobId);
            return storage.write(request.userId(), png, snapshot.widthOr(request.width()), snapshot.heightOr(request.height()));
        } catch (InterruptedException e) {
            if (internalJobId != null) {
                cancelBestEffort(internalJobId);
            }
            throw e;
        }
    }

    private void activateModel(GenerationRequest request) throws InterruptedException {
        if (request.modelId() == null || request.modelId().isBlank()) {
            throw new InternalServiceException("Internal service generation requires a modelId to activate");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("checkpoint", request.modelId().trim());

        HttpResponse<String> response = sendString(HttpRequest.newBuilder(uri("/api/v1/models/active"))
                .timeout(requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build());
        requireStatus(response, 200, "activate internal service model " + request.modelId());
    }

    private String submit(GenerationRequest request) throws InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("prompt", request.prompt());
        body.put("width", request.width());
        body.put("height", request.height());
        putIfPresent(body, "negative_prompt", request.negativePrompt());
        putIfPresent(body, "steps", request.numInferenceSteps());
        putIfPresent(body, "cfg", request.guidanceScale());
        putIfPresent(body, "sampler", request.sampler());
        putIfPresent(body, "seed", request.seed());

        HttpResponse<String> response = sendString(HttpRequest.newBuilder(uri("/api/v1/generate"))
                .timeout(requestTimeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toJson(body)))
                .build());

        if (response.statusCode() == 503) {
            throw new InternalServiceException("Internal service queue is full");
        }
        requireStatus(response, 202, "submit generation job");
        JsonNode json = readJson(response.body(), "submit generation job response");
        String jobId = text(json, "job_id");
        if (jobId == null || jobId.isBlank()) {
            throw new InternalServiceException("Internal service did not return a job_id");
        }
        return jobId;
    }

    private void recordInternalJobId(GenerationRequest request, String internalJobId) {
        if (request.jobId() != null) {
            jobRepository.updateInternalServiceJobId(request.jobId(), internalJobId);
        }
    }

    private JobSnapshot waitForCompletion(String internalJobId, IntConsumer progressListener)
            throws InterruptedException {
        Optional<JobSnapshot> sseSnapshot = waitForCompletionViaSse(internalJobId, progressListener);
        if (sseSnapshot.isPresent()) {
            return sseSnapshot.get();
        }
        return waitForCompletionViaPolling(internalJobId, progressListener);
    }

    private Optional<JobSnapshot> waitForCompletionViaSse(String internalJobId, IntConsumer progressListener)
            throws InterruptedException {
        HttpResponse<Stream<String>> response;
        try {
            response = httpClient.send(HttpRequest.newBuilder(uri("/api/v1/jobs/" + internalJobId + "/events"))
                    .timeout(requestTimeout())
                    .header("Accept", "text/event-stream")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofLines());
        } catch (IOException e) {
            return Optional.empty();
        }

        if (response.statusCode() != 200) {
            return Optional.empty();
        }

        SseEventBuilder eventBuilder = new SseEventBuilder();
        ProgressTracker progressTracker = new ProgressTracker();
        try (Stream<String> lines = response.body()) {
            var iterator = lines.iterator();
            while (iterator.hasNext()) {
                throwIfInterrupted();
                Optional<SseEvent> event = eventBuilder.accept(iterator.next());
                if (event.isPresent()) {
                    Optional<JobSnapshot> terminalSnapshot =
                            handleSseEvent(internalJobId, event.get(), progressTracker, progressListener);
                    if (terminalSnapshot.isPresent()) {
                        return terminalSnapshot;
                    }
                }
            }
        } catch (UncheckedIOException e) {
            return Optional.empty();
        }

        return Optional.empty();
    }

    private Optional<JobSnapshot> handleSseEvent(String internalJobId,
                                                SseEvent event,
                                                ProgressTracker progressTracker,
                                                IntConsumer progressListener)
            throws InterruptedException {
        if ("end".equals(event.event())) {
            return Optional.empty();
        }
        if ("error".equals(event.event())) {
            return Optional.empty();
        }
        if (event.data().isBlank() || "log".equals(event.event())) {
            return Optional.empty();
        }

        JsonNode json = readJson(event.data(), "generation job " + internalJobId + " SSE event");
        JobSnapshot snapshot = snapshotFromJson(json);
        if (snapshot.status() == null || snapshot.status().isBlank()) {
            return Optional.empty();
        }
        progressTracker.publish(snapshot, progressListener);
        if (snapshot.isTerminal()) {
            if (STATUS_COMPLETED.equals(snapshot.status())) {
                progressTracker.publish(100, progressListener);
            }
            return Optional.of(validateTerminalSnapshot(internalJobId, snapshot));
        }
        return Optional.empty();
    }

    private JobSnapshot waitForCompletionViaPolling(String internalJobId, IntConsumer progressListener)
            throws InterruptedException {
        int lastProgress = -1;
        while (true) {
            throwIfInterrupted();
            JobSnapshot snapshot = fetchJob(internalJobId);
            int percent = Math.max(lastProgress, snapshot.progressPercent());
            if (percent != lastProgress) {
                progressListener.accept(percent);
                throwIfInterrupted();
                lastProgress = percent;
            }

            String status = snapshot.status();
            if (status == null || status.isBlank()) {
                throw new InternalServiceException(
                        "Internal service returned a generation job without a status: " + internalJobId);
            }

            switch (status) {
                case STATUS_QUEUED, STATUS_RUNNING -> sleepBeforeNextPoll(internalJobId);
                case STATUS_COMPLETED -> {
                    if (lastProgress < 100) {
                        progressListener.accept(100);
                        throwIfInterrupted();
                    }
                    return validateTerminalSnapshot(internalJobId, snapshot);
                }
                case STATUS_FAILED, STATUS_CANCELLED -> validateTerminalSnapshot(internalJobId, snapshot);
                default -> throw new InternalServiceException(
                        "Internal service returned unknown job status for " + internalJobId + ": " + snapshot.status());
            }
        }
    }

    private JobSnapshot validateTerminalSnapshot(String internalJobId, JobSnapshot snapshot)
            throws InterruptedException {
        switch (snapshot.status()) {
            case STATUS_COMPLETED -> {
                if (!snapshot.imageReady()) {
                    throw new InternalServiceException(
                            "Internal service completed job " + internalJobId + " without a ready image");
                }
                return snapshot;
            }
            case STATUS_FAILED -> throw new InternalServiceException(
                    "Internal service failed job " + internalJobId + ": " + snapshot.errorOrDefault());
            case STATUS_CANCELLED -> throw new InterruptedException(
                    "Internal service cancelled job " + internalJobId);
            default -> throw new InternalServiceException(
                    "Internal service returned non-terminal status where terminal was expected for "
                            + internalJobId + ": " + snapshot.status());
        }
    }

    private JobSnapshot fetchJob(String internalJobId) throws InterruptedException {
        HttpResponse<String> response = sendString(HttpRequest.newBuilder(uri("/api/v1/jobs/" + internalJobId))
                .timeout(requestTimeout())
                .header("Accept", "application/json")
                .GET()
                .build());
        requireStatus(response, 200, "fetch generation job " + internalJobId);
        JsonNode json = readJson(response.body(), "generation job " + internalJobId + " response");
        return snapshotFromJson(json);
    }

    private byte[] downloadImage(String internalJobId) throws InterruptedException {
        HttpResponse<byte[]> response;
        try {
            response = httpClient.send(HttpRequest.newBuilder(uri("/api/v1/jobs/" + internalJobId + "/image"))
                    .timeout(requestTimeout())
                    .header("Accept", "image/png")
                    .GET()
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to download generated image for internal job " + internalJobId, e);
        }

        int status = response.statusCode();
        if (status == 409) {
            throw new InternalServiceException("Internal service image is not ready for job " + internalJobId);
        }
        if (status == 404 || status == 410) {
            throw new InternalServiceException(
                    "Internal service image for job " + internalJobId + " is missing, expired or already consumed");
        }
        if (status != 200) {
            throw new InternalServiceException(
                    "Internal service returned HTTP " + status + " while downloading image for job " + internalJobId);
        }
        return response.body();
    }

    private void sleepBeforeNextPoll(String internalJobId) throws InterruptedException {
        try {
            Thread.sleep(Math.max(1L, properties.getPollInterval().toMillis()));
        } catch (InterruptedException e) {
            cancelBestEffort(internalJobId);
            throw e;
        }
    }

    private void cancelBestEffort(String internalJobId) {
        boolean wasInterrupted = Thread.interrupted();
        try {
            httpClient.send(HttpRequest.newBuilder(uri("/api/v1/jobs/" + internalJobId))
                    .timeout(requestTimeout())
                    .DELETE()
                    .build(), HttpResponse.BodyHandlers.discarding());
        } catch (IOException | InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ignored) {
            // Best-effort cancellation; preserve the local cancellation path.
        } finally {
            if (wasInterrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private HttpResponse<String> sendString(HttpRequest request) throws InterruptedException {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new UncheckedIOException("Internal service request failed: " + request.uri(), e);
        }
    }

    private void requireStatus(HttpResponse<String> response, int expected, String action) {
        if (response.statusCode() != expected) {
            throw new InternalServiceException(
                    "Internal service returned HTTP " + response.statusCode() + " while trying to "
                            + action + errorSuffix(response.body()));
        }
    }

    private String toJson(Map<String, Object> body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (RuntimeException e) {
            throw new InternalServiceException("Failed to encode internal service request: " + e.getMessage());
        }
    }

    private JsonNode readJson(String body, String description) {
        try {
            return objectMapper.readTree(body);
        } catch (RuntimeException e) {
            throw new InternalServiceException(
                    "Failed to decode internal service " + description + ": " + e.getMessage());
        }
    }

    private URI uri(String path) {
        return URI.create(baseUrl + path);
    }

    private Duration requestTimeout() {
        return properties.getRequestTimeout();
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

    private static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value instanceof String s) {
            if (!s.isBlank()) {
                body.put(key, s);
            }
            return;
        }
        if (value != null) {
            body.put(key, value);
        }
    }

    private static String text(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }

    private static Integer intOrNull(JsonNode json, String field) {
        JsonNode value = json.get(field);
        return value == null || value.isNull() ? null : value.asInt();
    }

    private static void throwIfInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Generation cancelled");
        }
    }

    private static String errorSuffix(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        return ": " + body;
    }

    private static JobSnapshot snapshotFromJson(JsonNode json) {
        return new JobSnapshot(
                text(json, "status"),
                json.path("progress").asDouble(0.0),
                json.path("image_ready").asBoolean(false),
                text(json, "error"),
                intOrNull(json, "width"),
                intOrNull(json, "height"));
    }

    private record JobSnapshot(
            String status,
            double progress,
            boolean imageReady,
            String error,
            Integer width,
            Integer height
    ) {
        int progressPercent() {
            return (int) Math.round(Math.max(0.0, Math.min(1.0, progress)) * 100.0);
        }

        String errorOrDefault() {
            return error == null || error.isBlank() ? "unknown error" : error;
        }

        int widthOr(int fallback) {
            return width != null && width > 0 ? width : fallback;
        }

        int heightOr(int fallback) {
            return height != null && height > 0 ? height : fallback;
        }

        boolean isTerminal() {
            return STATUS_COMPLETED.equals(status)
                    || STATUS_FAILED.equals(status)
                    || STATUS_CANCELLED.equals(status);
        }
    }

    private record SseEvent(String event, String data) {
    }

    private static class ProgressTracker {

        private int lastProgress = -1;

        void publish(JobSnapshot snapshot, IntConsumer progressListener) throws InterruptedException {
            publish(snapshot.progressPercent(), progressListener);
        }

        void publish(int percent, IntConsumer progressListener) throws InterruptedException {
            int monotonicPercent = Math.max(lastProgress, Math.max(0, Math.min(100, percent)));
            if (monotonicPercent != lastProgress) {
                progressListener.accept(monotonicPercent);
                throwIfInterrupted();
                lastProgress = monotonicPercent;
            }
        }
    }

    private static class SseEventBuilder {

        private String event = "message";
        private final StringBuilder data = new StringBuilder();

        Optional<SseEvent> accept(String line) {
            if (line.isEmpty()) {
                if (data.isEmpty()) {
                    event = "message";
                    return Optional.empty();
                }
                SseEvent built = new SseEvent(event, data.toString());
                event = "message";
                data.setLength(0);
                return Optional.of(built);
            }
            if (line.startsWith(":")) {
                return Optional.empty();
            }
            if (line.startsWith("event:")) {
                event = line.substring("event:".length()).trim();
                return Optional.empty();
            }
            if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring("data:".length()).trim());
            }
            return Optional.empty();
        }
    }

    static class InternalServiceException extends RuntimeException {

        InternalServiceException(String message) {
            super(message);
        }
    }
}
