package com.ga.pixgen.event;

import java.util.UUID;

public record VerificationEmailRequestedEvent(String email, UUID tokenId) {
}
