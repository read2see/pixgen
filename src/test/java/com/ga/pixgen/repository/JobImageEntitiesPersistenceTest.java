package com.ga.pixgen.repository;

import com.ga.pixgen.model.Image;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

class JobImageEntitiesPersistenceTest extends AbstractPostgresContainerTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private JobRepository jobRepository;

    @Test
    void persistsJobWithAllFieldsAndDefaults() {
        Job job = newJob(1L, JobStatus.PENDING);

        Job saved = em.persistFlushFind(job);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(JobStatus.PENDING);
        assertThat(saved.getUserId()).isEqualTo(1L);
        assertThat(saved.getPrompt()).isEqualTo("a cat");
        assertThat(saved.getNegativePrompt()).isEqualTo("blurry");
        assertThat(saved.getWidth()).isEqualTo(512);
        assertThat(saved.getHeight()).isEqualTo(512);
        assertThat(saved.getNumInferenceSteps()).isEqualTo(20);
        assertThat(saved.getGuidanceScale()).isEqualTo(7.5);
        assertThat(saved.getSeed()).isEqualTo(42L);
        assertThat(saved.getSampler()).isEqualTo("euler");
        assertThat(saved.getModelId()).isEqualTo("runwayml/stable-diffusion-v1-5");
        assertThat(saved.getInternalServiceJobId()).isNull();
        assertThat(saved.getCreditsCost()).isEqualTo(1);
        assertThat(saved.getProgress()).isZero();
        assertThat(saved.isCancelRequested()).isFalse();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
        assertThat(saved.getVersion()).isNotNull();
        assertThat(saved.getClaimedByInstance()).isNull();
        assertThat(saved.getClaimedAt()).isNull();
        assertThat(saved.getStartedAt()).isNull();
        assertThat(saved.getCompletedAt()).isNull();
        assertThat(saved.getErrorMessage()).isNull();
    }

    @Test
    void incrementsVersionOnUpdate() {
        Job job = em.persistFlushFind(newJob(1L, JobStatus.PENDING));
        Long initialVersion = job.getVersion();

        job.setStatus(JobStatus.RUNNING);
        em.persistAndFlush(job);
        em.clear();

        Job loaded = em.find(Job.class, job.getId());
        assertThat(loaded.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(loaded.getVersion()).isGreaterThan(initialVersion);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void updateProgress_commitsWithoutCallerTransaction_andTouchesVersion() {
        Job saved = jobRepository.saveAndFlush(newJob(1L, JobStatus.RUNNING));
        Long initialVersion = saved.getVersion();

        int updated = jobRepository.updateProgress(saved.getId(), 45);

        Job loaded = jobRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(loaded.getProgress()).isEqualTo(45);
        assertThat(loaded.getVersion()).isGreaterThan(initialVersion);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void updateInternalServiceJobId_commitsWithoutCallerTransaction() {
        Job saved = jobRepository.saveAndFlush(newJob(1L, JobStatus.RUNNING));

        int updated = jobRepository.updateInternalServiceJobId(saved.getId(), "internal-job-123");

        Job loaded = jobRepository.findById(saved.getId()).orElseThrow();
        assertThat(updated).isEqualTo(1);
        assertThat(loaded.getInternalServiceJobId()).isEqualTo("internal-job-123");
    }

    @Test
    void persistsImageLinkedToJobAndUser() {
        Job job = em.persistFlushFind(newJob(2L, JobStatus.SUCCEEDED));

        Image image = new Image();
        image.setUserId(2L);
        image.setJob(job);
        image.setPrompt("a cat");
        image.setFilePath("u/2/abc.png");
        image.setMimeType("image/png");
        image.setFileSizeBytes(1024L);
        image.setWidth(512);
        image.setHeight(512);

        Image saved = em.persistFlushFind(image);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUserId()).isEqualTo(2L);
        assertThat(saved.getJob().getId()).isEqualTo(job.getId());
        assertThat(saved.getFilePath()).isEqualTo("u/2/abc.png");
        assertThat(saved.getMimeType()).isEqualTo("image/png");
        assertThat(saved.getFileSizeBytes()).isEqualTo(1024L);
        assertThat(saved.getWidth()).isEqualTo(512);
        assertThat(saved.getHeight()).isEqualTo(512);
        assertThat(saved.getCreatedAt()).isNotNull();
    }

    @Test
    void persistsImageWithGenerationColumns() {
        Job job = em.persistFlushFind(newJob(3L, JobStatus.SUCCEEDED));
        Image image = new Image();
        image.setUserId(3L);
        image.setJob(job);
        image.setPrompt("p");
        image.setNegativePrompt("n");
        image.setModelId("runwayml/stable-diffusion-v1-5");
        image.setSampler("euler");
        image.setNumInferenceSteps(20);
        image.setGuidanceScale(7.5);
        image.setSeed(42L);
        image.setScheduler("karras");
        image.setClipSkip(2);
        image.setLorasJson("[]");
        image.setExtrasJson("{\"k\":\"v\"}");
        image.setFilePath("u/3/x.png");
        image.setMimeType("image/png");
        image.setFileSizeBytes(10L);
        image.setWidth(64);
        image.setHeight(64);

        Image saved = em.persistFlushFind(image);

        assertThat(saved.getModelId()).isEqualTo("runwayml/stable-diffusion-v1-5");
        assertThat(saved.getNegativePrompt()).isEqualTo("n");
        assertThat(saved.getSampler()).isEqualTo("euler");
        assertThat(saved.getNumInferenceSteps()).isEqualTo(20);
        assertThat(saved.getGuidanceScale()).isEqualTo(7.5);
        assertThat(saved.getSeed()).isEqualTo(42L);
        assertThat(saved.getScheduler()).isEqualTo("karras");
        assertThat(saved.getClipSkip()).isEqualTo(2);
        assertThat(saved.getLorasJson()).isEqualTo("[]");
        assertThat(saved.getExtrasJson()).isEqualTo("{\"k\":\"v\"}");
    }

    private Job newJob(long userId, JobStatus status) {
        Job job = new Job();
        job.setUserId(userId);
        job.setStatus(status);
        job.setPrompt("a cat");
        job.setNegativePrompt("blurry");
        job.setWidth(512);
        job.setHeight(512);
        job.setNumInferenceSteps(20);
        job.setGuidanceScale(7.5);
        job.setSeed(42L);
        job.setSampler("euler");
        job.setModelId("runwayml/stable-diffusion-v1-5");
        job.setCreditsCost(1);
        return job;
    }
}
