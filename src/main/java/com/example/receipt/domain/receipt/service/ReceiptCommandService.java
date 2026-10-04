package com.example.receipt.domain.receipt.service;

import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.receipt.dto.FieldCorrections;
import com.example.receipt.domain.receipt.entity.AuditEvent;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.exception.ReceiptConflictException;
import com.example.receipt.domain.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.domain.receipt.model.AuditAction;
import com.example.receipt.domain.receipt.model.ReceiptData;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import com.example.receipt.domain.receipt.model.ReviewDecision;
import com.example.receipt.domain.receipt.model.RuleResult;
import com.example.receipt.domain.receipt.repository.AuditEventRepository;
import com.example.receipt.domain.receipt.repository.ReceiptRepository;
import com.example.receipt.domain.receipt.validation.ReceiptStatusRouter;
import com.example.receipt.domain.receipt.validation.ValidationEngine;
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

    private final ReceiptAccess access;
    private final com.example.receipt.domain.employee.service.CurrentEmployee current;
    private final ReceiptRepository receiptRepository;
    private final AuditEventRepository auditRepository;
    private final ValidationEngine validationEngine;
    private final ReceiptStatusRouter statusRouter;
    private final Clock clock;

    @Transactional
    public Receipt correctFields(Long receiptId, long expectedVersion,
                                 FieldCorrections corrections) {
        if (!CLEARABLE_FIELDS.containsAll(corrections.getClearFields())) {
            throw new IllegalArgumentException("지원하지 않는 clearFields 값이 포함되어 있습니다.");
        }
        Receipt receipt = get(receiptId);
        access.modify(receipt);
        ensureVersion(receipt, expectedVersion);
        ensureNotTerminal(receipt);

        ReceiptData before = receipt.currentData();
        ReceiptData after = corrections.applyTo(before);
        boolean duplicate = receipt.ruleResults().stream()
                .anyMatch(result -> result.code().equals("DUPLICATE_SUBMISSION") && result.failed());
        List<RuleResult> results = validationEngine.validate(after, duplicate);
        ReceiptStatus previousStatus = receipt.status();
        ReceiptStatus nextStatus = statusRouter.route(after, results);
        if (nextStatus == ReceiptStatus.AUTO_APPROVED) {
            var employee = current.require();
            // 직원 보정과 본인 영수증 보정은 규칙을 통과해도 다른 검수자의 확인이 필요합니다.
            if (employee.role() == EmployeeRole.EMPLOYEE || employee.id().equals(receipt.ownerEmployeeId())) {
                nextStatus = ReceiptStatus.NEEDS_REVIEW;
            }
        }
        Instant now = Instant.now(clock);
        receipt.updateData(after, results, nextStatus, now);

        Map<String, Object> correctionDetails = createCorrectionDetails(before, after, results);
        auditRepository.save(new AuditEvent(receipt.id(), now, current.actor(),
                AuditAction.FIELDS_CORRECTED, previousStatus, nextStatus,
                correctionDetails));
        receiptRepository.flush();
        return receipt;
    }

    @Transactional
    public Receipt decide(Long receiptId, long expectedVersion,
                          ReviewDecision decision, String note) {
        Receipt receipt = get(receiptId);
        access.review(receipt);
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
        auditRepository.save(new AuditEvent(receipt.id(), now, current.actor(),
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
        if (receipt.status() == null) {
            throw new ReceiptConflictException("AI 추출 작업이 완료된 뒤 검수할 수 있습니다.");
        }
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
