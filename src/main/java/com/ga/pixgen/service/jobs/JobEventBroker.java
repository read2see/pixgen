package com.ga.pixgen.service.jobs;

import com.ga.pixgen.dto.JobEventDto;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-process pub/sub hub fanning {@link JobEventDto}s out to the SSE
 * clients of the user that owns the job.
 *
 * <p>State is held in a {@link ConcurrentHashMap} keyed by user id whose
 * values are {@link CopyOnWriteArrayList}s of registered emitters. Both
 * structures are intentionally lock-free: registration and publication
 * race freely, and per-emitter completion/timeout/error callbacks evict
 * dead listeners without cooperation from the publisher loop. A failing
 * {@link SseEmitter#send(SseEmitter.SseEventBuilder) send} during fan-out
 * is treated as a hung-up client: the emitter is removed and completed
 * with the underlying error so its async request is released.</p>
 *
 * <p>The {@code register(userId, jobIdFilter)} overload backs the
 * per-job {@code GET /api/jobs/{id}/stream} endpoint by storing the
 * filter alongside the emitter; events whose {@code jobId} does not
 * match are skipped without going through the wire.</p>
 */
@Component
public class JobEventBroker {

    /**
     * Default emitter timeout. SSE streams are long-lived; we set 30 minutes
     * so the reverse proxy or browser usually decides when to recycle the
     * connection rather than letting Spring time it out mid-job.
     */
    public static final long DEFAULT_TIMEOUT_MS = 30L * 60L * 1000L;

    private final long timeoutMs;
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<EmitterRegistration>> emittersByUser =
            new ConcurrentHashMap<>();

    public JobEventBroker() {
        this(DEFAULT_TIMEOUT_MS);
    }

    public JobEventBroker(long timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    /**
     * Register an emitter that receives every event for the given user.
     *
     * @param userId the user id value
     * @return the SseEmitter result
     */
    public SseEmitter register(Long userId) {
        return register(userId, null);
    }

    /**
     * Register an emitter scoped to a single job. Events for any other job
     * owned by the same user are dropped before they reach the wire.
     *
     * @param userId the user id value
     * @param jobIdFilter the job id filter value
     * @return the SseEmitter result
     */
    public SseEmitter register(Long userId, Long jobIdFilter) {
        return register(new SseEmitter(timeoutMs), userId, jobIdFilter);
    }

    /**
     * Wire an externally-provided emitter into the broker. Visible to the
     * tests so they can inject mock {@link SseEmitter}s, and reused by the
     * public overloads above.
     *
     * @param emitter the emitter value
     * @param userId the user id value
     * @param jobIdFilter the job id filter value
     * @return the SseEmitter result
     */
    SseEmitter register(SseEmitter emitter, Long userId, Long jobIdFilter) {
        Objects.requireNonNull(emitter, "emitter");
        Objects.requireNonNull(userId, "userId");
        EmitterRegistration registration = new EmitterRegistration(emitter, jobIdFilter);
        emittersByUser
                .computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>())
                .add(registration);
        emitter.onCompletion(() -> remove(userId, registration));
        emitter.onTimeout(() -> {
            remove(userId, registration);
            emitter.complete();
        });
        emitter.onError(throwable -> remove(userId, registration));
        return emitter;
    }

    /**
     * Fan a single event out to every registered emitter that belongs to
     * {@code event.userId()} and either has no job filter or has a filter
     * matching {@code event.jobId()}. Emitters that throw on {@code send}
     * are evicted and completed with the error so they do not poison the
     * map.
     *
     * @param event the event value
     */
    public void publish(JobEventDto event) {
        Long userId = event.userId();
        if (userId == null) {
            return;
        }
        CopyOnWriteArrayList<EmitterRegistration> registrations = emittersByUser.get(userId);
        if (registrations == null || registrations.isEmpty()) {
            return;
        }
        for (EmitterRegistration registration : registrations) {
            if (registration.jobIdFilter != null
                    && !registration.jobIdFilter.equals(event.jobId())) {
                continue;
            }
            SseEmitter.SseEventBuilder builder = SseEmitter.event()
                    .name(event.type())
                    .data(event);
            if (event.jobId() != null) {
                builder.id(String.valueOf(event.jobId()));
            }
            try {
                registration.emitter.send(builder);
            } catch (IOException | IllegalStateException ex) {
                registrations.remove(registration);
                try {
                    registration.emitter.completeWithError(ex);
                } catch (RuntimeException ignored) {
                    // Already completed by the container; nothing more to do.
                }
            }
        }
    }

    /**
     * Test helper: how many emitters are registered for {@code userId}.
     *
     * @param userId the user id value
     * @return the int result
     */
    public int emitterCount(Long userId) {
        CopyOnWriteArrayList<EmitterRegistration> list = emittersByUser.get(userId);
        return list == null ? 0 : list.size();
    }

    /**
     * Test helper: total emitters across all users.
     *
     * @return the int result
     */
    public int totalEmitterCount() {
        int total = 0;
        for (CopyOnWriteArrayList<EmitterRegistration> list : emittersByUser.values()) {
            total += list.size();
        }
        return total;
    }

    private void remove(Long userId, EmitterRegistration registration) {
        CopyOnWriteArrayList<EmitterRegistration> list = emittersByUser.get(userId);
        if (list != null) {
            list.remove(registration);
        }
    }

    /**
     * Pairs an emitter with its (optional) per-job filter. Object identity
     * is enough for {@link CopyOnWriteArrayList#remove(Object)} so we keep
     * this a thin value holder.
     */
    private static final class EmitterRegistration {
        final SseEmitter emitter;
        final Long jobIdFilter;

        EmitterRegistration(SseEmitter emitter, Long jobIdFilter) {
            this.emitter = emitter;
            this.jobIdFilter = jobIdFilter;
        }
    }
}
