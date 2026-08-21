package com.example.receipt.extraction;

public record ExtractionRequest(byte[] imageBytes, String contentType, String fileName) {
    public ExtractionRequest {
        imageBytes = imageBytes.clone();
    }
}
