package com.alejandro.mtonotification.application.dto.admin;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** {@code POST /admin/test-email}: a que direccion. */
public record TestEmailRequest(@NotBlank @Email @Size(max = 320) String to) {
}
