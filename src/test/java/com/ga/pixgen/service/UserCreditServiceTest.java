package com.ga.pixgen.service;

import com.ga.pixgen.exception.ResourceNotFoundException;
import com.ga.pixgen.model.User;
import com.ga.pixgen.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserCreditServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserCreditService userCreditService;

    @Test
    void increaseCredits_addsAmountAndSavesUser() {
        User user = new User();
        user.setId(7L);
        user.setCredits(10);
        when(userRepository.findById(7L)).thenReturn(Optional.of(user));
        when(userRepository.save(user)).thenReturn(user);

        User updated = userCreditService.increaseCredits(7L, 15);

        assertThat(updated.getCredits()).isEqualTo(25);
        verify(userRepository).save(user);
    }

    @Test
    void increaseCredits_throwsWhenUserDoesNotExist() {
        when(userRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userCreditService.increaseCredits(404L, 15))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
