package com.example.receipt.domain.employee.dto;
import jakarta.validation.constraints.*;
public record SetPasswordRequest(@NotBlank @Size(max = 100) String token,
                                 @NotBlank @Size(min = 12, max = 72) String password) {
    @Override public String toString() { return "SetPasswordRequest[REDACTED]"; }
}
