package com.example.receipt.domain.receipt.controller;

import com.example.receipt.domain.employee.service.CurrentEmployeeService;
import com.example.receipt.domain.receipt.dto.AuditEventResponse;
import com.example.receipt.domain.receipt.dto.CorrectFieldsRequest;
import com.example.receipt.domain.receipt.dto.ReceiptAcceptedResponse;
import com.example.receipt.domain.receipt.dto.ReceiptResponse;
import com.example.receipt.domain.receipt.dto.ReviewDecisionRequest;
import com.example.receipt.domain.receipt.dto.UploadResult;
import com.example.receipt.domain.receipt.dto.ReceiptListRequest;
import com.example.receipt.domain.receipt.dto.ReceiptPageResponse;
import com.example.receipt.domain.receipt.service.ReceiptCommandService;
import com.example.receipt.domain.receipt.service.ReceiptQueryService;
import com.example.receipt.domain.receipt.service.ReceiptUploadService;
import jakarta.validation.Valid;
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
    private final CurrentEmployeeService currentEmployeeService;
    private final ReceiptUploadService uploadService;
    private final ReceiptQueryService queryService;
    private final ReceiptCommandService commandService;

    /** 모든 역할에서 로그인한 직원 본인의 제출 목록만 반환합니다. */
    @GetMapping
    public ReceiptPageResponse mine(@Valid @ModelAttribute ReceiptListRequest request) {
        return queryService.findMyReceipts(request);
    }

    /** 검수자 본인 제출 건과 소유자 없는 과거 자료는 검수 대기 목록에서 제외합니다. */
    @GetMapping("/review-queue")
    public ReceiptPageResponse reviewQueue(@Valid @ModelAttribute ReceiptListRequest request) {
        return queryService.findReviewQueue(request);
    }

    /**
     * @param idempotencyKey 동일 업로드 요청 재전송 시 중복 처리를 막는 요청 키
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ReceiptAcceptedResponse> upload(
            @RequestHeader(value = "Idempotency-Key", required = false)
            @Size(max = 100) String idempotencyKey,
            @RequestPart("file") MultipartFile file) throws IOException {
        UploadResult result = uploadService.upload("internal", idempotencyKey, file.getOriginalFilename(),
                file.getContentType(), file.getBytes(), currentEmployeeService.getCurrentEmployee().id());
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
        return ReceiptResponse.from(queryService.getReceipt(id), queryService.getExtractionJob(id).status());
    }

    @GetMapping("/{id}/audit-events")
    public List<AuditEventResponse> auditEvents(@PathVariable Long id) {
        return queryService.getAuditEvents(id).stream().map(AuditEventResponse::from).toList();
    }

    @PatchMapping("/{id}/fields")
    public ReceiptResponse correctFields(@PathVariable Long id, @Valid @RequestBody CorrectFieldsRequest request) {
        return ReceiptResponse.from(commandService.correctFields(id, request.version(),
                request.toCorrections()), queryService.getExtractionJob(id).status());
    }

    @PostMapping("/{id}/decision")
    public ReceiptResponse decide(@PathVariable Long id, @Valid @RequestBody ReviewDecisionRequest request) {
        return ReceiptResponse.from(commandService.decide(id, request.version(),
                request.decision(), request.note()), queryService.getExtractionJob(id).status());
    }
}
