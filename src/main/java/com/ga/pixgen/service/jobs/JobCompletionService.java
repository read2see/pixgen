package com.ga.pixgen.service.jobs;

import com.ga.pixgen.model.Image;
import com.ga.pixgen.model.ImageMetadata;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.repository.ImageMetadataRepository;
import com.ga.pixgen.repository.ImageRepository;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.repository.UserRepository;
import com.ga.pixgen.service.images.StoredImage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Encapsulates the success-completion critical section invoked by
 * {@link JobWorker}: deduct credits, persist {@link Image} +
 * {@link ImageMetadata}, and flip the {@link Job} to
 * {@link com.ga.pixgen.model.JobStatus#SUCCEEDED}.
 *
 * <p>Lives in its own bean so the four mutations run inside a single
 * Spring-managed transaction via the standard {@code @Transactional}
 * proxy. The worker invokes this method while holding the per-user
 * {@link UserJobLocks lock}, which means a single user's parallel jobs
 * serialise their credit-deduction races at the in-process layer in
 * addition to the database-level conditional update.</p>
 */
@Service
public class JobCompletionService {

    private final UserRepository userRepository;
    private final ImageRepository imageRepository;
    private final ImageMetadataRepository imageMetadataRepository;
    private final JobRepository jobRepository;

    public JobCompletionService(UserRepository userRepository,
                                ImageRepository imageRepository,
                                ImageMetadataRepository imageMetadataRepository,
                                JobRepository jobRepository) {
        this.userRepository = userRepository;
        this.imageRepository = imageRepository;
        this.imageMetadataRepository = imageMetadataRepository;
        this.jobRepository = jobRepository;
    }

    /**
     * Run the success-completion critical section for {@code job} and
     * its produced {@code stored} artifact. Returns {@code true} when
     * credits were deducted and the row was flipped to {@code SUCCEEDED};
     * {@code false} when the conditional credit {@code UPDATE} returned
     * zero rows — the worker treats that as the {@code INSUFFICIENT_CREDITS}
     * failure path.
     *
     * @param job the job value
     * @param stored the stored value
     * @return the boolean result
     */
    @Transactional
    public boolean completeSuccess(Job job, StoredImage stored) {
        int rows = userRepository.deductCreditsIfSufficient(job.getUserId(), job.getCreditsCost());
        if (rows == 0) {
            return false;
        }
        Image savedImage = imageRepository.save(buildImage(job, stored));
        imageMetadataRepository.save(buildMetadata(job, savedImage));
        jobRepository.markSucceeded(job.getId());
        return true;
    }

    private static Image buildImage(Job job, StoredImage stored) {
        Image image = new Image();
        image.setUserId(job.getUserId());
        image.setJob(job);
        image.setPrompt(job.getPrompt());
        image.setFilePath(stored.relativePath());
        image.setMimeType(stored.mimeType());
        image.setFileSizeBytes(stored.sizeBytes());
        image.setWidth(stored.width());
        image.setHeight(stored.height());
        return image;
    }

    private static ImageMetadata buildMetadata(Job job, Image savedImage) {
        ImageMetadata metadata = new ImageMetadata();
        metadata.setImage(savedImage);
        metadata.setModelName(job.getModelName());
        metadata.setSampler(job.getSampler());
        metadata.setSteps(job.getSteps());
        metadata.setCfgScale(job.getCfgScale());
        metadata.setSeed(job.getSeed());
        return metadata;
    }
}
