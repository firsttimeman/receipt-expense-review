package com.example.receipt.quality;

public record ImageQualityResult(ImageQualityStatus status, Integer width, Integer height, String reason) {
}
