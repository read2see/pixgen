package com.ga.pixgen.event;

import com.ga.pixgen.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EmailEventListenerTest {

    @Mock
    private EmailService emailService;

    @InjectMocks
    private EmailEventListener listener;

    @Test
    void onVerificationEmailRequested_delegatesToEmailService() {
        String email = "user@example.com";
        UUID tokenId = UUID.randomUUID();

        listener.onVerificationEmailRequested(new VerificationEmailRequestedEvent(email, tokenId));

        verify(emailService).sendVerificationEmail(email, tokenId);
    }

    @Test
    void onPasswordResetEmailRequested_delegatesToEmailService() {
        String email = "user@example.com";
        UUID tokenId = UUID.randomUUID();

        listener.onPasswordResetEmailRequested(new PasswordResetEmailRequestedEvent(email, tokenId));

        verify(emailService).sendPasswordResetEmail(email, tokenId);
    }
}
