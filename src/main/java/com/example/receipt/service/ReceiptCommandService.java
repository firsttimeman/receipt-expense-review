package com.example.receipt.service;

import com.example.receipt.domain.*;
import com.example.receipt.entity.AuditEvent;
import com.example.receipt.entity.Receipt;
import com.example.receipt.exception.ReceiptConflictException;
import com.example.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.service.model.FieldCorrections;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.validation.ReceiptStatusRouter;
import com.example.receipt.validation.ValidationEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
/**
 * 최종적인 승인 단계 필드를 지우고 수정할지를 결정
 */
public class ReceiptCommandService {
    private static final Set<String> CLEARABLE_FIELDS = Set.of(
            "merchant", "date", "totalAmount", "businessRegistrationNumber", "paymentMethod", "lineItems");

    private final ReceiptRepository receiptRepository;
    private final AuditEventRepository auditRepository;
    private final ValidationEngine validationEngine;
    private final ReceiptStatusRouter statusRouter;
    private final Clock clock;

    @Transactional
    public Receipt correctFields(Long receiptId, long expectedVersion, String reviewerId,
                                 FieldCorrections corrections) {
        if (!CLEARABLE_FIELDS.containsAll(corrections.getClearFields())) {
            throw new IllegalArgumentException("지원하지 않는 clearFields 값이 포함되어 있습니다.");
        }
        Receipt receipt = get(receiptId);
        ensureVersion(receipt, expectedVersion); // version lock 처리하기
        ensureNotTerminal(receipt);

        ReceiptData before = receipt.currentData();
        ReceiptData after = corrections.applyTo(before);
        boolean duplicate = receipt.ruleResults().stream()
                .anyMatch(result -> result.code().equals("DUPLICATE_SUBMISSION") && result.failed());
        List<RuleResult> results = validationEngine.validate(after, duplicate);
        ReceiptStatus previousStatus = receipt.status();
        ReceiptStatus nextStatus = statusRouter.route(after, results);
        Instant now = Instant.now(clock);
        receipt.updateData(after, results, nextStatus, now);

        Map<String, Object> correctionDetails = createCorrectionDetails(before, after, results);
        auditRepository.save(new AuditEvent(receipt.id(), now, reviewerId,
                AuditAction.FIELDS_CORRECTED, previousStatus, nextStatus,
                correctionDetails));
        receiptRepository.flush();
        return receipt;
    }

    @Transactional
    public Receipt decide(Long receiptId, long expectedVersion, String reviewerId,
                          ReviewDecision decision, String note) {
        Receipt receipt = get(receiptId);
        ensureVersion(receipt, expectedVersion);
        ensureNotTerminal(receipt);
        if (receipt.status() == ReceiptStatus.NEEDS_RECAPTURE || receipt.status() == ReceiptStatus.UNREADABLE) {
            throw new ReceiptConflictException("재촬영 또는 판독 불가 상태는 필드를 보완한 뒤 결정해야 합니다.");
        }

        ReceiptStatus previous = receipt.status();
        ReceiptStatus next = decision == ReviewDecision.APPROVE ? ReceiptStatus.APPROVED : ReceiptStatus.REJECTED;
        AuditAction action = decision == ReviewDecision.APPROVE
                ? AuditAction.REVIEW_APPROVED : AuditAction.REVIEW_REJECTED;
        Instant now = Instant.now(clock);
        receipt.changeStatus(next, now);
        Map<String, Object> decisionDetails = createDecisionDetails(note);
        auditRepository.save(new AuditEvent(receipt.id(), now, reviewerId,
                action, previous, next, decisionDetails));
        receiptRepository.flush();
        return receipt;
    }

    private Receipt get(Long id) {
        return receiptRepository.findById(id).orElseThrow(() -> new ReceiptNotFoundException(id));
    }

    private void ensureVersion(Receipt receipt, long expectedVersion) {
        if (receipt.version() != expectedVersion) {
            throw new ReceiptConflictException("다른 검수자가 먼저 변경했습니다. 최신 영수증을 다시 조회하세요.");
        }
    }

    private void ensureNotTerminal(Receipt receipt) {
        if (receipt.status() == ReceiptStatus.APPROVED || receipt.status() == ReceiptStatus.REJECTED) {
            throw new ReceiptConflictException("이미 최종 처리된 영수증은 변경할 수 없습니다.");
        }
    }

    private Map<String, Object> createCorrectionDetails(
            ReceiptData before,
            ReceiptData after,
            List<RuleResult> results
    ) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (before != null) {
            details.put("before", before);
        }

        details.put("after", after);
        details.put("rules", results);
        return details;
    }

    private Map<String, Object> createDecisionDetails(String note) {
        Map<String, Object> details = new LinkedHashMap<>();

        if (note != null) {
            details.put("note", note);
        }

        return details;
    }
}
