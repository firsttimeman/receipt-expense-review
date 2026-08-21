package com.example.receipt.service;

import com.example.receipt.domain.*;
import com.example.receipt.entity.AuditEvent;
import com.example.receipt.entity.IdempotencyRecord;
import com.example.receipt.entity.Receipt;
import com.example.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.validation.ReceiptStatusRouter;
import com.example.receipt.validation.ValidationEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
/**
 * 영수증 생성 서비스
 */
public class ReceiptPersistenceService {
    private final ReceiptRepository receiptRepository;
    private final AuditEventRepository auditRepository;
    private final IdempotencyRecordRepository idempotencyRepository;
    private final ValidationEngine validationEngine;
    private final ReceiptStatusRouter statusRouter;
    private final Clock clock;

    /**
     * 영수증과 중복 요청 방지 키, 감사 이벤트를 하나의 트랜잭션으로 저장한다.
     * idempotencyKey가 없으면 일반 업로드로 처리한다.
     */
    @Transactional
    public Receipt create(Receipt receipt, String idempotencyKey, List<AuditEvent> auditEvents) {
        receiptRepository.saveAndFlush(receipt);
        auditEvents.forEach(event -> event.attachTo(receipt.id()));
        // 재전송 시 최초 영수증을 찾을 수 있도록 중복 요청 방지 키와 영수증 ID를 연결한다.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyRepository.save(new IdempotencyRecord(receipt.companyId(),
                    idempotencyKey, receipt.id(), Instant.now(clock)));
        }
        auditRepository.saveAll(auditEvents);
        receiptRepository.flush();
        idempotencyRepository.flush();
        return receipt;
    }

    @Transactional
    public Receipt markDuplicate(Long receiptId) {
        Receipt receipt = receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(receiptId));
        boolean alreadyMarked = receipt.ruleResults().stream()
                .anyMatch(result -> result.code().equals("DUPLICATE_SUBMISSION") && result.failed());
        if (alreadyMarked) {
            return receipt;
        }
        ReceiptStatus previous = receipt.status();
        List<RuleResult> results = validationEngine.validate(receipt.currentData(), true);
        ReceiptStatus next = isTerminal(previous) ? previous : statusRouter.route(receipt.currentData(), results);
        Instant now = Instant.now(clock);
        receipt.updateData(receipt.currentData(), results, next, now);
        auditRepository.save(new AuditEvent(receipt.id(), now, "system",
                AuditAction.DUPLICATE_DETECTED, previous, next,
                Map.of("ruleCode", "DUPLICATE_SUBMISSION")));
        return receipt;
    }

    private boolean isTerminal(ReceiptStatus status) {
        return status == ReceiptStatus.APPROVED || status == ReceiptStatus.REJECTED;
    }
}
