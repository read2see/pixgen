package com.ga.pixgen.event;

import java.util.UUID;

public record PasswordResetEmailRequestedEvent(String email, UUID tokenId) {
}
