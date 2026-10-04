package com.example.receipt.domain.extraction.dto;

public record ExtractionRequest(byte[] imageBytes, String contentType, String fileName) {
    public ExtractionRequest {
        imageBytes = imageBytes.clone();
    }
}
