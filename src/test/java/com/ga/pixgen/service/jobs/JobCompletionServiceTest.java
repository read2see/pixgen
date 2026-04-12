package com.ga.pixgen.service.jobs;

import com.ga.pixgen.model.Image;
import com.ga.pixgen.model.ImageMetadata;
import com.ga.pixgen.model.Job;
import com.ga.pixgen.model.JobStatus;
import com.ga.pixgen.repository.ImageMetadataRepository;
import com.ga.pixgen.repository.ImageRepository;
import com.ga.pixgen.repository.JobRepository;
import com.ga.pixgen.repository.UserRepository;
import com.ga.pixgen.service.images.StoredImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link JobCompletionService}, the {@code @Transactional}
 * service that owns the four mutations that make a job a successful
 * one: credit deduction, image persistence, metadata persistence, and
 * the {@link JobStatus#SUCCEEDED} status flip.
 *
 * <p>The service contract is:</p>
 * <ul>
 *     <li>If the conditional credit {@code UPDATE} returns zero rows the
 *         method short-circuits without touching the image / metadata /
 *         job tables and returns {@code false} so the worker can run
 *         the {@code INSUFFICIENT_CREDITS} failure path.</li>
 *     <li>If the deduction succeeds, the {@code Image} and
 *         {@code ImageMetadata} are persisted with fields drawn from
 *         the job and the produced {@link StoredImage}, and the
 *         {@code Job} is flipped to {@code SUCCEEDED}; the method
 *         returns {@code true}.</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class JobCompletionServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ImageRepository imageRepository;

    @Mock
    private ImageMetadataRepository imageMetadataRepository;

    @Mock
    private JobRepository jobRepository;

    @InjectMocks
    private JobCompletionService completionService;

    private Job job;
    private StoredImage stored;

    @BeforeEach
    void setUp() {
        job = new Job();
        job.setId(101L);
        job.setUserId(7L);
        job.setPrompt("a cat");
        job.setSteps(20);
        job.setCfgScale(7.5);
        job.setSeed(42L);
        job.setSampler("euler-a");
        job.setModelName("sd-1.5");
        job.setCreditsCost(1);

        stored = new StoredImage("7/cafe.png", 9876L, 64, 32, "image/png");
    }

    @Test
    void completeSuccess_returnsFalse_andPersistsNothing_whenCreditDeductionReturnsZero() {
        when(userRepository.deductCreditsIfSufficient(7L, 1)).thenReturn(0);

        boolean result = completionService.completeSuccess(job, stored);

        assertThat(result).isFalse();
        verify(imageRepository, never()).save(any());
        verify(imageMetadataRepository, never()).save(any());
        verify(jobRepository, never()).markSucceeded(any());
    }

    @Test
    void completeSuccess_persistsImage_metadata_andMarksSucceeded_whenCreditsAreDeducted() {
        when(userRepository.deductCreditsIfSufficient(7L, 1)).thenReturn(1);
        when(imageRepository.save(any(Image.class))).thenAnswer(invocation -> {
            Image image = invocation.getArgument(0);
            image.setId(555L);
            return image;
        });
        when(imageMetadataRepository.save(any(ImageMetadata.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        boolean result = completionService.completeSuccess(job, stored);

        assertThat(result).isTrue();

        ArgumentCaptor<Image> imageCaptor = ArgumentCaptor.forClass(Image.class);
        verify(imageRepository).save(imageCaptor.capture());
        Image savedImage = imageCaptor.getValue();
        assertThat(savedImage.getUserId()).isEqualTo(7L);
        assertThat(savedImage.getJob()).isSameAs(job);
        assertThat(savedImage.getFilePath()).isEqualTo("7/cafe.png");
        assertThat(savedImage.getMimeType()).isEqualTo("image/png");
        assertThat(savedImage.getFileSizeBytes()).isEqualTo(9876L);
        assertThat(savedImage.getWidth()).isEqualTo(64);
        assertThat(savedImage.getHeight()).isEqualTo(32);
        assertThat(savedImage.getPrompt()).isEqualTo("a cat");

        ArgumentCaptor<ImageMetadata> metadataCaptor =
                ArgumentCaptor.forClass(ImageMetadata.class);
        verify(imageMetadataRepository).save(metadataCaptor.capture());
        ImageMetadata metadata = metadataCaptor.getValue();
        assertThat(metadata.getImage().getId()).isEqualTo(555L);
        assertThat(metadata.getModelName()).isEqualTo("sd-1.5");
        assertThat(metadata.getSampler()).isEqualTo("euler-a");
        assertThat(metadata.getSteps()).isEqualTo(20);
        assertThat(metadata.getCfgScale()).isEqualTo(7.5);
        assertThat(metadata.getSeed()).isEqualTo(42L);

        verify(jobRepository).markSucceeded(101L);
    }
}
