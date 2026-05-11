package com.ga.pixgen.service;

import com.ga.pixgen.dto.UserStatsResponse;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.ImageRepository;
import com.ga.pixgen.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserStatsServiceTest {

    @Mock
    private JobRepository jobRepository;

    @Mock
    private ImageRepository imageRepository;

    @InjectMocks
    private UserStatsService userStatsService;

    @Test
    void getStats_returnsActiveJobsGeneratedImagesAndCredits() {
        User user = new User();
        user.setId(7L);
        user.setCredits(50);
        when(jobRepository.countActiveByUser(7L)).thenReturn(2L);
        when(imageRepository.countByUserId(7L)).thenReturn(9L);

        UserStatsResponse stats = userStatsService.getStats(user);

        assertThat(stats.activeJobs()).isEqualTo(2);
        assertThat(stats.generatedImages()).isEqualTo(9);
        assertThat(stats.credits()).isEqualTo(50);
    }
}
