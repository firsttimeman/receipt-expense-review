package com.example.receipt.api;

import com.example.receipt.api.dto.AuditEventResponse;
import com.example.receipt.api.dto.CorrectFieldsRequest;
import com.example.receipt.api.dto.ReceiptResponse;
import com.example.receipt.api.dto.ReceiptAcceptedResponse;
import com.example.receipt.api.dto.ReviewDecisionRequest;
import com.example.receipt.service.ReceiptCommandService;
import com.example.receipt.service.ReceiptQueryService;
import com.example.receipt.service.ReceiptUploadService;
import com.example.receipt.service.model.UploadResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api/receipts")
@Validated
@RequiredArgsConstructor
public class ReceiptController {
    private final ReceiptUploadService uploadService;
    private final ReceiptQueryService queryService;
    private final ReceiptCommandService commandService;

    /**
     * @param idempotencyKey 동일 업로드 요청 재전송 시 중복 처리를 막는 요청 키
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptAcceptedResponse> upload(
            @RequestHeader("X-Company-Id")
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String companyId,
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Size(max = 100) String idempotencyKey,
            @RequestPart("file") MultipartFile file) throws IOException {
        UploadResult result = uploadService.upload(companyId, idempotencyKey, file.getOriginalFilename(),
                file.getContentType(), file.getBytes());
        ReceiptAcceptedResponse body = ReceiptAcceptedResponse.from(result);
        if (result.created()) {
            return ResponseEntity.accepted()
                    .location(URI.create("/api/receipts/" + body.receiptId()))
                    .body(body);
        }
        return ResponseEntity.ok()
                .header("X-Idempotent-Replay", Boolean.toString(result.idempotentReplay()))
                .body(body);
    }

    @GetMapping("/{id}")
    public ReceiptResponse get(@PathVariable Long id) {
        return ReceiptResponse.from(queryService.get(id), queryService.getJob(id).status());
    }

    @GetMapping("/{id}/audit-events")
    public List<AuditEventResponse> auditEvents(@PathVariable Long id) {
        return queryService.auditLog(id).stream().map(AuditEventResponse::from).toList();
    }

    @PatchMapping("/{id}/fields")
    public ReceiptResponse correctFields(@PathVariable Long id, @Valid @RequestBody CorrectFieldsRequest request) {
        return ReceiptResponse.from(commandService.correctFields(id, request.version(), request.reviewerId(),
                request.toCorrections()), queryService.getJob(id).status());
    }

    @PostMapping("/{id}/decision")
    public ReceiptResponse decide(@PathVariable Long id, @Valid @RequestBody ReviewDecisionRequest request) {
        return ReceiptResponse.from(commandService.decide(id, request.version(), request.reviewerId(),
                request.decision(), request.note()), queryService.getJob(id).status());
    }
}
