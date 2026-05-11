package com.ga.pixgen.service;

import com.ga.pixgen.dto.UserStatsResponse;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.ImageRepository;
import com.ga.pixgen.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserStatsService {

    private final JobRepository jobRepository;
    private final ImageRepository imageRepository;

    @Transactional(readOnly = true)
    public UserStatsResponse getStats(User user) {
        Long userId = user.getId();
        return new UserStatsResponse(
                jobRepository.countActiveByUser(userId),
                imageRepository.countByUserId(userId),
                user.getCredits());
    }
}
