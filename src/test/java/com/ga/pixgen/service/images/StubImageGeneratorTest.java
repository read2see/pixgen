package com.ga.pixgen.service.images;

import com.ga.pixgen.config.ImagesProperties;
import com.ga.pixgen.config.JobsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests for {@link StubImageGenerator}, the placeholder backend that stands
 * in for a real diffusion runtime in phase 2.
 *
 * <p>Behaviour pinned by these tests:</p>
 * <ul>
 *   <li>Sleeps a randomised amount in {@code [stubMinMs, stubMaxMs]}, emitting
 *       progress at roughly each 10% with monotonically non-decreasing values
 *       that finish at exactly 100.</li>
 *   <li>Persists a valid PNG via {@link LocalImageStorage} that round-trips
 *       through {@link ImageIO} with the requested dimensions.</li>
 *   <li>Translates a thread interrupt into {@link InterruptedException} —
 *       this is how the worker honours user-initiated cancellation.</li>
 *   <li>Cleans up any partially-written file when interrupted, so a cancelled
 *       job leaves no orphan bytes on disk.</li>
 * </ul>
 */
class StubImageGeneratorTest {

    @TempDir
    Path tempDir;

    private LocalImageStorage storage;
    private JobsProperties jobsProperties;

    @BeforeEach
    void setUp() {
        ImagesProperties imagesProperties = new ImagesProperties();
        imagesProperties.setLocalDir(tempDir.toString());
        storage = new LocalImageStorage(imagesProperties);

        jobsProperties = new JobsProperties();
        // Keep tests fast and deterministic. Range still has > 0 width so the
        // randomised duration code path is exercised.
        jobsProperties.setStubMinMs(50L);
        jobsProperties.setStubMaxMs(120L);
    }

    @Test
    void generate_returnsStoredImageWithRequestedDimensionsAndPngMime() throws Exception {
        StubImageGenerator generator = new StubImageGenerator(jobsProperties, storage, new Random(0));
        GenerationRequest request = new GenerationRequest(11L, 22L, 64, 32, "a red square", 1234L);

        StoredImage stored = generator.generate(request, progress -> {});

        assertThat(stored.width()).isEqualTo(64);
        assertThat(stored.height()).isEqualTo(32);
        assertThat(stored.mimeType()).isEqualTo("image/png");
        assertThat(stored.sizeBytes()).isPositive();
        assertThat(stored.relativePath()).startsWith("22/").endsWith(".png");
    }

    @Test
    void generate_writesDecodablePngWithMatchingDimensions() throws Exception {
        StubImageGenerator generator = new StubImageGenerator(jobsProperties, storage, new Random(0));
        GenerationRequest request = new GenerationRequest(1L, 2L, 48, 24, "anything", 7L);

        StoredImage stored = generator.generate(request, progress -> {});

        Path file = storage.resolve(stored.relativePath());
        byte[] bytes = Files.readAllBytes(file);
        BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(decoded)
                .as("written file must be a valid PNG that ImageIO can decode")
                .isNotNull();
        assertThat(decoded.getWidth()).isEqualTo(48);
        assertThat(decoded.getHeight()).isEqualTo(24);
    }

    @Test
    void generate_emitsProgress_monotonicallyEndingAt100() throws Exception {
        StubImageGenerator generator = new StubImageGenerator(jobsProperties, storage, new Random(0));
        GenerationRequest request = new GenerationRequest(1L, 5L, 16, 16, "p", 1L);
        List<Integer> progress = new ArrayList<>();

        generator.generate(request, progress::add);

        assertThat(progress)
                .as("progress callbacks must fire across the run, roughly every 10%")
                .hasSizeGreaterThanOrEqualTo(10);
        assertThat(progress)
                .as("progress must never decrease so SSE clients can rely on the value")
                .isSorted();
        assertThat(progress.get(0)).isGreaterThanOrEqualTo(0);
        assertThat(progress.get(progress.size() - 1))
                .as("the final progress value must be exactly 100")
                .isEqualTo(100);
    }

    @Test
    void generate_runtimeFallsWithinConfiguredRange() throws Exception {
        // Tighten the window so we have a meaningful upper bound to assert on.
        jobsProperties.setStubMinMs(80L);
        jobsProperties.setStubMaxMs(160L);
        StubImageGenerator generator = new StubImageGenerator(jobsProperties, storage, new Random(0));
        GenerationRequest request = new GenerationRequest(1L, 3L, 16, 16, "p", 1L);

        long start = System.nanoTime();
        generator.generate(request, p -> {});
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs)
                .as("generator must respect the configured minimum sleep window")
                .isGreaterThanOrEqualTo(jobsProperties.getStubMinMs());
        assertThat(elapsedMs)
                .as("generator must not blow past the configured maximum by more than a small slack")
                .isLessThan(jobsProperties.getStubMaxMs() + 750L);
    }

    @Test
    void generate_throwsInterruptedException_whenThreadIsInterrupted() throws Exception {
        // Long enough that the test thread reliably interrupts mid-flight.
        jobsProperties.setStubMinMs(2_000L);
        jobsProperties.setStubMaxMs(2_000L);
        StubImageGenerator generator = new StubImageGenerator(jobsProperties, storage, new Random(0));
        GenerationRequest request = new GenerationRequest(1L, 9L, 16, 16, "p", 1L);

        AtomicReference<Throwable> caught = new AtomicReference<>();
        CountDownLatch started = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = pool.submit(() -> {
                started.countDown();
                try {
                    generator.generate(request, p -> {});
                } catch (Throwable t) {
                    caught.set(t);
                }
            });

            assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
            // Give the generator a tick to enter its sleep loop before interrupting.
            Thread.sleep(100);
            future.cancel(true);
            // Wait for the worker to observe the interrupt and exit.
            try {
                future.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // expected — cancelled future
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(caught.get())
                .as("an interrupted generator must surface the interruption to its caller")
                .isInstanceOf(InterruptedException.class);
    }

    @Test
    void generate_doesNotLeaveFileBehind_whenInterrupted() throws Exception {
        jobsProperties.setStubMinMs(2_000L);
        jobsProperties.setStubMaxMs(2_000L);
        StubImageGenerator generator = new StubImageGenerator(jobsProperties, storage, new Random(0));
        GenerationRequest request = new GenerationRequest(1L, 77L, 16, 16, "p", 1L);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> future = pool.submit(() -> {
                try {
                    generator.generate(request, p -> {});
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            });
            Thread.sleep(150);
            future.cancel(true);
            try {
                future.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // expected
            }
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
        }

        Path userDir = tempDir.resolve("77");
        if (Files.exists(userDir)) {
            try (var stream = Files.list(userDir)) {
                assertThat(stream.toList())
                        .as("a cancelled generation must not leave orphan bytes on disk")
                        .isEmpty();
            }
        }
    }

    @Test
    void generate_rejectsNonPositiveDimensions() {
        StubImageGenerator generator = new StubImageGenerator(jobsProperties, storage, new Random(0));

        assertThatThrownBy(() ->
                generator.generate(new GenerationRequest(1L, 1L, 0, 16, "p", 1L), p -> {}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                generator.generate(new GenerationRequest(1L, 1L, 16, -1, "p", 1L), p -> {}))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
