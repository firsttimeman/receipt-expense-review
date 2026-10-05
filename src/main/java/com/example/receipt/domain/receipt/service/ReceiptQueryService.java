package com.example.receipt.domain.receipt.service;

import com.example.receipt.domain.employee.service.CurrentEmployee;
import com.example.receipt.domain.extraction.entity.ReceiptExtractionJob;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.receipt.entity.AuditEvent;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.dto.ReceiptListItemResponse;
import com.example.receipt.domain.receipt.dto.ReceiptListRequest;
import com.example.receipt.domain.receipt.dto.ReceiptPageResponse;
import com.example.receipt.domain.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.domain.receipt.repository.AuditEventRepository;
import com.example.receipt.domain.receipt.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

import static com.example.receipt.domain.receipt.repository.ReceiptSpecifications.*;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReceiptQueryService {
    private final ReceiptAccess access;
    private final ReceiptRepository receiptRepository;
    private final AuditEventRepository auditRepository;
    private final ReceiptExtractionJobRepository jobRepository;

    private final CurrentEmployee current;

    public ReceiptPageResponse mine(ReceiptListRequest request) {
        // 소유자는 요청 파라미터를 사용하지 않고 인증된 직원으로만 결정합니다.
        return list(ownedBy(current.require().id()), request);
    }

    @PreAuthorize("hasAnyRole('REVIEWER', 'ADMIN')")
    public ReceiptPageResponse reviewQueue(ReceiptListRequest request) {
        return list(awaitingReviewBy(current.require().id()), request);
    }

    private ReceiptPageResponse list(Specification<Receipt> scope, ReceiptListRequest request) {
        request.validate();
        var results = receiptRepository.findAll(scope
                .and(withStatus(request.status()))
                .and(submittedBetween(request.fromInclusive(), request.toExclusive())), request.pageable());

        // 페이지에 포함된 작업 상태를 한 번에 읽어 영수증별 추가 조회를 피합니다.
        var receiptIds = results.getContent().stream().map(Receipt::id).toList();
        var jobs = receiptIds.isEmpty() ? List.<ReceiptExtractionJob>of() : jobRepository.findByReceiptIdIn(receiptIds);
        var jobStatuses = jobs.stream().collect(Collectors.toMap(ReceiptExtractionJob::receiptId, ReceiptExtractionJob::status));
        return ReceiptPageResponse.from(results.map(receipt ->
                ReceiptListItemResponse.from(receipt, jobStatuses.get(receipt.id()))));
    }

    public Receipt get(Long id) {
        Receipt receipt = receiptRepository.findById(id).orElseThrow(() -> new ReceiptNotFoundException(id));
        access.read(receipt);
        return receipt;
    }

    public ReceiptExtractionJob getJob(Long receiptId) {
        get(receiptId);
        return jobRepository.findByReceiptId(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(receiptId));
    }

    public List<AuditEvent> auditLog(Long id) {
        get(id);
        return auditRepository.findByReceiptIdOrderByOccurredAtAsc(id);
    }
}
