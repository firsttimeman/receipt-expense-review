package com.example.receipt.domain.extraction.extractor;

import com.example.receipt.domain.extraction.dto.ExtractionRequest;
import com.example.receipt.domain.extraction.dto.ExtractionResult;
public interface ReceiptExtractor {
    ExtractionResult extract(ExtractionRequest request);
}
