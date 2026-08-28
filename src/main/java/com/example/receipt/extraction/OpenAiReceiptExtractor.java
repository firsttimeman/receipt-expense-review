package com.example.receipt.extraction;

import com.example.receipt.config.ReceiptProperties;
import com.example.receipt.domain.ReceiptData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public class OpenAiReceiptExtractor implements ReceiptExtractor {
    private static final String INSTRUCTIONS = """
            한국어 카드 영수증 한 장에서 구조화된 값을 추출하세요.
            이미지에서 명확히 읽을 수 없는 값은 절대 추측하지 말고 null을 반환하세요.
            날짜는 YYYY-MM-DD, 금액은 숫자로 반환하세요. confidence 값은 생성하지 마세요.
            """;

    private final ReceiptProperties.OpenAi properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public OpenAiReceiptExtractor(ReceiptProperties.OpenAi properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(toMillis(properties.getConnectTimeout()));
        requestFactory.setReadTimeout(toMillis(properties.getResponseTimeout()));
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        if (properties.getApiKey().isBlank()) {
            throw new ExtractionException("OPENAI_API_KEY가 설정되지 않았습니다.");
        }

        String mediaType = request.contentType() == null ? MediaType.IMAGE_JPEG_VALUE : request.contentType();
        String dataUrl = "data:%s;base64,%s".formatted(mediaType,
                Base64.getEncoder().encodeToString(request.imageBytes()));

        Map<String, Object> body = Map.of(
                "model", properties.getModel(),
                "store", false,
                "input", List.of(Map.of(
                        "role", "user",
                        "content", List.of(
                                Map.of("type", "input_text", "text", INSTRUCTIONS),
                                Map.of("type", "input_image", "image_url", dataUrl, "detail", "high")
                        )
                )),
                "text", Map.of("format", receiptFormat())
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/v1/responses")
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + properties.getApiKey())
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            String outputText = findOutputText(response);
            ReceiptData data = objectMapper.readValue(outputText, ReceiptData.class);
            return new ExtractionResult(data, "openai", properties.getModel());
        } catch (ExtractionException exception) {
            throw exception;
        } catch (RestClientResponseException exception) {
            throw new ExtractionException(
                    "OpenAI 요청에 실패했습니다. HTTP 상태: " + exception.getStatusCode().value(),
                    exception);
        } catch (ResourceAccessException exception) {
            throw new ExtractionException("OpenAI 연결 또는 응답 제한 시간을 초과했습니다.", exception);
        } catch (RestClientException exception) {
            throw new ExtractionException("OpenAI 응답 본문을 해석하지 못했습니다.", exception);
        } catch (JsonProcessingException exception) {
            throw new ExtractionException(
                    "OpenAI 구조화 응답을 영수증 데이터로 변환하지 못했습니다.", exception);
        } catch (Exception exception) {
            throw new ExtractionException(
                    "OpenAI 영수증 추출 중 예상하지 못한 오류가 발생했습니다.", exception);
        }
    }

    private int toMillis(java.time.Duration duration) {
        return Math.toIntExact(Math.min(duration.toMillis(), Integer.MAX_VALUE));
    }

    private Map<String, Object> receiptFormat() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> nullableNumber = Map.of("type", List.of("number", "null"));
        Map<String, Object> lineItem = Map.of(
                "type", "object",
                "additionalProperties", false,
                "properties", Map.of(
                        "name", nullableString,
                        "quantity", Map.of("type", List.of("integer", "null")),
                        "unitPrice", nullableNumber,
                        "amount", nullableNumber
                ),
                "required", List.of("name", "quantity", "unitPrice", "amount")
        );
        return Map.of(
                "type", "json_schema",
                "name", "korean_receipt",
                "strict", true,
                "schema", Map.of(
                        "type", "object",
                        "additionalProperties", false,
                        "properties", Map.of(
                                "merchant", nullableString,
                                "date", nullableString,
                                "totalAmount", nullableNumber,
                                "businessRegistrationNumber", nullableString,
                                "paymentMethod", nullableString,
                                "lineItems", Map.of("type", "array", "items", lineItem)
                        ),
                        "required", List.of("merchant", "date", "totalAmount",
                                "businessRegistrationNumber", "paymentMethod", "lineItems")
                )
        );
    }

    private String findOutputText(JsonNode response) {
        if (response == null) {
            throw new ExtractionException("OpenAI 응답 본문이 없습니다.");
        }
        for (JsonNode output : response.path("output")) {
            for (JsonNode content : output.path("content")) {
                if ("output_text".equals(content.path("type").asText()) && content.hasNonNull("text")) {
                    return content.path("text").asText();
                }
                if ("refusal".equals(content.path("type").asText())) {
                    throw new ExtractionException("모델이 이미지 처리를 거부했습니다.");
                }
            }
        }
        throw new ExtractionException("OpenAI 응답에서 구조화 출력을 찾지 못했습니다.");
    }
}
