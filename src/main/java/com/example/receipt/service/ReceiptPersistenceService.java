package com.example.receipt.service;

import com.example.receipt.domain.*;
import com.example.receipt.entity.AuditEvent;
import com.example.receipt.entity.IdempotencyRecord;
import com.example.receipt.entity.Receipt;
import com.example.receipt.entity.ReceiptExtractionJob;
import com.example.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.IdempotencyRecordRepository;
import com.example.receipt.repository.ReceiptRepository;
import com.example.receipt.repository.ReceiptExtractionJobRepository;
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
    private final ReceiptExtractionJobRepository jobRepository;
    private final ValidationEngine validationEngine;
    private final ReceiptStatusRouter statusRouter;
    private final Clock clock;

    /**
     * 영수증, 추출 작업, 중복 요청 방지 키, 감사 이벤트를 하나의 트랜잭션으로 저장한다.
     * idempotencyKey가 없으면 일반 업로드로 처리한다.
     */
    @Transactional
    public ReceiptExtractionJob createQueued(Receipt receipt, String imageStorageKey,
                                             String idempotencyKey, List<AuditEvent> auditEvents) {
        receiptRepository.saveAndFlush(receipt);
        ReceiptExtractionJob job = jobRepository.save(new ReceiptExtractionJob(
                receipt.id(), imageStorageKey, Instant.now(clock)));
        auditEvents.forEach(event -> event.attachTo(receipt.id()));
        // 재전송 시 최초 영수증을 찾을 수 있도록 중복 요청 방지 키와 영수증 ID를 연결한다.
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            idempotencyRepository.save(new IdempotencyRecord(receipt.companyId(),
                    idempotencyKey, receipt.id(), Instant.now(clock)));
        }
        auditRepository.saveAll(auditEvents);
        receiptRepository.flush();
        jobRepository.flush();
        idempotencyRepository.flush();
        return job;
    }

    @Transactional
    public Receipt markDuplicate(Long receiptId) {
        // 새 영수증을 만들지 않고, 처음 제출되어 이미 저장된 영수증을 조회한다.
        Receipt receipt = receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(receiptId));

        // Receipt, Job과 감사 이벤트에 동일한 중복 감지 시각을 기록한다.
        Instant now = Instant.now(clock);

        // status가 null이면 접수는 끝났지만 AI 추출과 업무 상태 결정은 아직 끝나지 않은 상태다.
        if (receipt.status() == null) {
            // 처리 전 중복 여부는 추출 데이터가 없는 Receipt가 아니라 해당 ExtractionJob에 보관한다.
            ReceiptExtractionJob job = jobRepository.findByReceiptId(receiptId)
                    .orElseThrow(() -> new IllegalStateException("영수증 추출 작업을 찾을 수 없습니다."));

            // 같은 이미지가 여러 번 재전송되어도 중복 표시와 감사 로그는 최초 한 번만 반영한다.
            if (!job.duplicateDetected()) {
                // 나중에 Processor가 이 값을 검증 엔진에 전달할 수 있도록 중복 사실을 표시한다.
                job.markDuplicate(now);

                // 아직 업무 상태는 결정할 수 없으므로 상태 변경 없이 검증을 미뤘다는 사실을 기록한다.
                auditRepository.save(new AuditEvent(receipt.id(), now, "system",
                        AuditAction.DUPLICATE_DETECTED, null, null,
                        Map.of("ruleCode", "DUPLICATE_SUBMISSION", "deferred", true)));
            }

            // 두 번째 Receipt를 만들지 않고 최초 접수된 Receipt를 그대로 반환한다.
            return receipt;
        }

        // 여기부터는 AI 추출과 최초 규칙 검증이 끝나 Receipt 업무 상태가 이미 존재하는 경우다.
        // 현재 규칙 결과에 중복 제출 실패가 이미 반영돼 있는지 확인한다.
        boolean alreadyMarked = receipt.ruleResults().stream()
                .anyMatch(result -> result.code().equals("DUPLICATE_SUBMISSION") && result.failed());

        // 이미 중복 처리된 영수증이면 규칙 재계산, 버전 증가와 감사 로그 중복 생성을 피한다.
        if (alreadyMarked) {
            return receipt;
        }

        // 감사 로그에 상태 변경 전후를 남기기 위해 현재 업무 상태를 보관한다.
        ReceiptStatus previous = receipt.status();

        // 기존 추출 데이터는 유지하고 duplicate=true 조건을 포함해 모든 결정론적 규칙을 다시 계산한다.
        List<RuleResult> results = validationEngine.validate(receipt.currentData(), true);

        // 최종 승인·반려는 시스템이 뒤집지 않고, 그 외 상태만 새 규칙 결과에 따라 다시 라우팅한다.
        ReceiptStatus next = isTerminal(previous) ? previous : statusRouter.route(receipt.currentData(), results);

        // 필드 값은 바꾸지 않고 새 규칙 결과, 업무 상태와 수정 시각만 Receipt에 반영한다.
        receipt.updateData(receipt.currentData(), results, next, now);

        // 중복 감지 사실과 실제 상태 변경 전후를 감사 로그로 남긴다.
        auditRepository.save(new AuditEvent(receipt.id(), now, "system",
                AuditAction.DUPLICATE_DETECTED, previous, next,
                Map.of("ruleCode", "DUPLICATE_SUBMISSION")));

        // 변경 감지는 트랜잭션 커밋 시 DB에 반영되며, 호출자에게 기존 Receipt를 반환한다.
        return receipt;
    }

    private boolean isTerminal(ReceiptStatus status) {
        return status == ReceiptStatus.APPROVED || status == ReceiptStatus.REJECTED;
    }
}
