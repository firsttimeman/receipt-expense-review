package com.example.receipt.extraction;

import com.example.receipt.config.ReceiptProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiReceiptExtractorTest {
    @Test
    void missingApiKeyFailsLocallyWithoutNetworkCall() {
        OpenAiReceiptExtractor extractor = new OpenAiReceiptExtractor(
                new ReceiptProperties.OpenAi("", "gpt-5.4-mini", "https://api.openai.com"),
                new ObjectMapper());

        assertThatThrownBy(() -> extractor.extract(new ExtractionRequest(new byte[]{1}, "image/png", "x.png")))
                .isInstanceOf(ExtractionException.class)
                .hasMessageContaining("OPENAI_API_KEY");
    }
}
