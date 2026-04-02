package com.ga.pixgen.dto;

import java.util.UUID;

public record ResetPasswordRequest(UUID token, String newPassword) {
}
