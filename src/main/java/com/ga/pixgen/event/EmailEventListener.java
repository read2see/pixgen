package com.ga.pixgen.event;

import com.ga.pixgen.service.EmailService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class EmailEventListener {

    private final EmailService emailService;

    @Async("mailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onVerificationEmailRequested(VerificationEmailRequestedEvent event) {
        emailService.sendVerificationEmail(event.email(), event.tokenId());
    }

    @Async("mailTaskExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPasswordResetEmailRequested(PasswordResetEmailRequestedEvent event) {
        emailService.sendPasswordResetEmail(event.email(), event.tokenId());
    }
}
