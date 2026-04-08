package com.ga.pixgen.service.jobs;

import com.ga.pixgen.dto.JobEventDto;
import com.ga.pixgen.model.JobStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for {@link JobEventBroker}, the SSE fan-out hub described in
 * the phase-2 plan. The broker keeps a {@link java.util.concurrent.ConcurrentHashMap
 * ConcurrentHashMap} of {@link java.util.concurrent.CopyOnWriteArrayList
 * CopyOnWriteArrayList} of {@link SseEmitter}s keyed by user, fans out
 * {@link JobEventDto} events to every emitter belonging to that user, and
 * removes dead emitters on {@link IOException} or completion callbacks so
 * the map cannot grow without bound.
 */
class JobEventBrokerTest {

    private JobEventBroker broker;

    @BeforeEach
    void setUp() {
        broker = new JobEventBroker(30_000L);
    }

    @Test
    void register_addsEmitterToUserList() {
        SseEmitter emitter = mock(SseEmitter.class);

        broker.register(emitter, 7L, null);

        assertThat(broker.emitterCount(7L)).isEqualTo(1);
        assertThat(broker.totalEmitterCount()).isEqualTo(1);
    }

    @Test
    void publish_fanOutsToAllEmittersForUser() throws Exception {
        SseEmitter a = mock(SseEmitter.class);
        SseEmitter b = mock(SseEmitter.class);
        broker.register(a, 7L, null);
        broker.register(b, 7L, null);

        broker.publish(JobEventDto.progress(101L, 7L, 50));

        verify(a, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(b, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void publish_doesNotDeliverToEmittersOfOtherUsers() throws Exception {
        SseEmitter mine = mock(SseEmitter.class);
        SseEmitter other = mock(SseEmitter.class);
        broker.register(mine, 7L, null);
        broker.register(other, 8L, null);

        broker.publish(JobEventDto.progress(101L, 7L, 50));

        verify(mine, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(other, never()).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void publish_isNoOp_whenNoEmittersRegisteredForUser() {
        // Should not throw NPE or otherwise fail.
        broker.publish(JobEventDto.progress(101L, 7L, 50));

        assertThat(broker.totalEmitterCount()).isZero();
    }

    @Test
    void publish_removesEmitterAndContinues_whenSendThrowsIOException() throws Exception {
        SseEmitter dead = mock(SseEmitter.class);
        SseEmitter alive = mock(SseEmitter.class);
        doThrow(new IOException("client disconnected"))
                .when(dead).send(any(SseEmitter.SseEventBuilder.class));
        broker.register(dead, 7L, null);
        broker.register(alive, 7L, null);

        broker.publish(JobEventDto.progress(101L, 7L, 50));

        assertThat(broker.emitterCount(7L))
                .as("dead emitter must be evicted; healthy one must stay")
                .isEqualTo(1);
        verify(alive, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(dead, atLeastOnce()).completeWithError(any(IOException.class));
    }

    @Test
    void publish_continuesDelivering_evenAfterAnEarlierEmitterFailed() throws Exception {
        SseEmitter dead = mock(SseEmitter.class);
        SseEmitter healthyA = mock(SseEmitter.class);
        SseEmitter healthyB = mock(SseEmitter.class);
        doThrow(new IOException("boom"))
                .when(dead).send(any(SseEmitter.SseEventBuilder.class));
        // Order matters: the dead emitter is registered first so the loop
        // proves that a mid-iteration failure does not abort fan-out.
        broker.register(dead, 7L, null);
        broker.register(healthyA, 7L, null);
        broker.register(healthyB, 7L, null);

        broker.publish(JobEventDto.progress(101L, 7L, 25));

        verify(healthyA, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(healthyB, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        assertThat(broker.emitterCount(7L)).isEqualTo(2);
    }

    @Test
    void register_withJobFilter_onlyDeliversMatchingEvents() throws Exception {
        SseEmitter filtered = mock(SseEmitter.class);
        SseEmitter unfiltered = mock(SseEmitter.class);
        broker.register(filtered, 7L, 555L);
        broker.register(unfiltered, 7L, null);

        broker.publish(JobEventDto.progress(999L, 7L, 25));

        verify(filtered, never()).send(any(SseEmitter.SseEventBuilder.class));
        verify(unfiltered, times(1)).send(any(SseEmitter.SseEventBuilder.class));

        broker.publish(JobEventDto.progress(555L, 7L, 75));

        verify(filtered, times(1)).send(any(SseEmitter.SseEventBuilder.class));
        verify(unfiltered, times(2)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void emitter_isRemovedFromMap_whenCompletionCallbackFires() {
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<Runnable> completionCaptor = ArgumentCaptor.forClass(Runnable.class);

        broker.register(emitter, 7L, null);

        verify(emitter).onCompletion(completionCaptor.capture());
        completionCaptor.getValue().run();

        assertThat(broker.emitterCount(7L)).isZero();
    }

    @Test
    void emitter_isRemovedFromMap_whenTimeoutCallbackFires() {
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<Runnable> timeoutCaptor = ArgumentCaptor.forClass(Runnable.class);

        broker.register(emitter, 7L, null);

        verify(emitter).onTimeout(timeoutCaptor.capture());
        timeoutCaptor.getValue().run();

        assertThat(broker.emitterCount(7L)).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitter_isRemovedFromMap_whenErrorCallbackFires() {
        SseEmitter emitter = mock(SseEmitter.class);
        ArgumentCaptor<Consumer<Throwable>> errorCaptor = ArgumentCaptor.forClass(Consumer.class);

        broker.register(emitter, 7L, null);

        verify(emitter).onError(errorCaptor.capture());
        errorCaptor.getValue().accept(new IOException("network blip"));

        assertThat(broker.emitterCount(7L)).isZero();
    }

    @Test
    void publish_statusEvent_invokesSendOnce() throws Exception {
        SseEmitter emitter = mock(SseEmitter.class);
        broker.register(emitter, 7L, null);

        broker.publish(JobEventDto.status(101L, 7L, JobStatus.SUCCEEDED));

        verify(emitter, times(1)).send(any(SseEmitter.SseEventBuilder.class));
    }

    @Test
    void register_publicOverloads_createOwnEmitter() {
        SseEmitter all = broker.register(7L);
        SseEmitter forJob = broker.register(7L, 555L);

        assertThat(all).isNotNull();
        assertThat(forJob).isNotNull();
        assertThat(broker.emitterCount(7L)).isEqualTo(2);
    }

    @Test
    void concurrentRegisterAndPublish_doesNotCorruptMap() throws Exception {
        int publishers = 4;
        int registrations = 200;
        int eventsPerPublisher = 200;

        ExecutorService pool = Executors.newFixedThreadPool(publishers + 1);
        try {
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(publishers + 1);

            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < registrations; i++) {
                        broker.register(mock(SseEmitter.class), 1L, null);
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });

            for (int p = 0; p < publishers; p++) {
                pool.submit(() -> {
                    try {
                        start.await();
                        for (int i = 0; i < eventsPerPublisher; i++) {
                            broker.publish(JobEventDto.progress((long) i, 1L, i % 100));
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(broker.emitterCount(1L)).isEqualTo(registrations);
    }
}
