package com.ga.pixgen.service;

import com.ga.pixgen.exception.ResourceNotFoundException;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserCreditService {

    private final UserRepository userRepository;

    @Transactional
    public User increaseCredits(Long userId, int amount) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User", userId));
        int current = user.getCredits() == null ? 0 : user.getCredits();
        user.setCredits(Math.addExact(current, amount));
        return userRepository.save(user);
    }
}
