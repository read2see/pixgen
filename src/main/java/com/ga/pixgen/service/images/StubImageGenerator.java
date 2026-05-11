package com.ga.pixgen.service.images;

import com.ga.pixgen.config.JobsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;
import java.util.function.IntConsumer;

/**
 * Placeholder backend used until a real diffusion runtime is wired in. The
 * stub serves three purposes that the production system needs end-to-end:
 *
 * <ol>
 *   <li>Burn a configurable, randomised slice of wall-clock time so the
 *       scheduler, semaphore, per-user caps and SSE plumbing can be exercised
 *       against realistic timings without GPU dependencies.</li>
 *   <li>Emit progress callbacks at roughly each 10% so the {@code JobEventBroker}
 *       fan-out is non-trivial.</li>
 *   <li>Produce a small but valid PNG via {@link ImageIO} so the
 *       {@code ImageController} file-streaming endpoint has real bytes to serve.</li>
 * </ol>
 *
 * <p>Cancellation is honoured by checking {@link Thread#isInterrupted()} before
 * each progress tick and using {@link Thread#sleep} between ticks, which itself
 * raises {@link InterruptedException} if the worker thread is interrupted by
 * {@link java.util.concurrent.Future#cancel(boolean) Future.cancel(true)}. Any
 * partial bytes that may have been written are removed before the exception
 * propagates so a cancelled job leaves no orphan files on disk.</p>
 */
@Component
public class StubImageGenerator implements ImageGenerationStrategy {

    /** Number of progress ticks across a single run; matches the plan's "every ~10%". */
    private static final int PROGRESS_TICKS = 10;

    private final JobsProperties properties;
    private final LocalImageStorage storage;
    private final Random random;

    @Autowired
    public StubImageGenerator(JobsProperties properties, LocalImageStorage storage) {
        this(properties, storage, new Random());
    }

    /**
     * Test-only ctor that pins the RNG for deterministic durations.
     *
     * @param properties the properties value
     * @param storage the storage value
     * @param random the random value
     */
    StubImageGenerator(JobsProperties properties, LocalImageStorage storage, Random random) {
        this.properties = properties;
        this.storage = storage;
        this.random = random;
    }

    @Override
    public StoredImage generate(GenerationRequest request, IntConsumer progressListener)
            throws InterruptedException {
        if (request.width() <= 0 || request.height() <= 0) {
            throw new IllegalArgumentException(
                    "width and height must be positive; got " + request.width() + "x" + request.height());
        }

        long totalSleepMs = pickSleep();
        long perTickMs = Math.max(1L, totalSleepMs / PROGRESS_TICKS);

        // Emit a 0% tick first so SSE clients see an immediate transition into
        // the running phase before any real work happens.
        progressListener.accept(0);

        for (int i = 1; i <= PROGRESS_TICKS; i++) {
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Generation cancelled before progress tick " + i);
            }
            Thread.sleep(perTickMs);
            int percent = (int) Math.round(100.0 * i / PROGRESS_TICKS);
            progressListener.accept(percent);
        }

        byte[] png = renderPlaceholderPng(request);

        StoredImage stored;
        try {
            stored = storage.write(request.userId(), png, request.width(), request.height());
        } catch (RuntimeException e) {
            // The storage layer can be slow (filesystem flush). If the worker
            // gets cancelled mid-write the IOException surfaces here; preserve
            // the interrupt semantics so the worker translates it correctly.
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("Generation cancelled during persist");
            }
            throw e;
        }

        // After the file is on disk, do one last interruption check so a cancel
        // racing the persist still cleans up rather than reporting success.
        if (Thread.currentThread().isInterrupted()) {
            try {
                storage.delete(stored.relativePath());
            } catch (RuntimeException ignored) {
                // best-effort cleanup
            }
            throw new InterruptedException("Generation cancelled after persist");
        }

        return stored;
    }

    private long pickSleep() {
        long min = Math.max(0L, properties.getStubMinMs());
        long max = Math.max(min, properties.getStubMaxMs());
        if (max == min) {
            return min;
        }
        return min + (long) (random.nextDouble() * (max - min));
    }

    /**
     * Renders a small horizontal gradient seeded by the request so successive
     * runs for the same job produce identical bytes. The gradient is intentionally
     * cheap — this is a test placeholder, not the production renderer.
     *
     * @param request the request value
     * @return the byte[] result
     */
    private byte[] renderPlaceholderPng(GenerationRequest request) {
        BufferedImage image = new BufferedImage(request.width(), request.height(), BufferedImage.TYPE_INT_RGB);
        long seed = request.seed() == null ? 0L : request.seed();
        Random localRandom = new Random(seed);
        int hueShift = localRandom.nextInt(360);
        for (int x = 0; x < request.width(); x++) {
            float hue = ((hueShift + (x * 360f / Math.max(1, request.width()))) % 360f) / 360f;
            int rgb = Color.HSBtoRGB(hue, 0.6f, 0.9f);
            for (int y = 0; y < request.height(); y++) {
                image.setRGB(x, y, rgb);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to encode placeholder PNG", e);
        }
    }
}
