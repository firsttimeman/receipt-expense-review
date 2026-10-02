package com.example.receipt.domain.extraction.quality;

public record ImageQualityResult(ImageQualityStatus status, Integer width, Integer height, String reason) {
}
