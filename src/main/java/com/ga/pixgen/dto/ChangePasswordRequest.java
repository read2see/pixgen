package com.ga.pixgen.dto;

public record ChangePasswordRequest(String currentPassword, String newPassword) {
}
