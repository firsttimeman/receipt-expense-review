package com.example.receipt.domain.receipt.dto;

import com.example.receipt.domain.receipt.model.ReviewDecision;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public record ReviewDecisionRequest(
        @NotNull @PositiveOrZero Long version, //todo PositiveOrZero 이게 뭐지?
        @NotBlank String reviewerId,
        @NotNull ReviewDecision decision,
        String note
) {
}
