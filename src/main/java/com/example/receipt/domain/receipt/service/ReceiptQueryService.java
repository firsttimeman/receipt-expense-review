package com.example.receipt.domain.receipt.service;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.service.CurrentEmployeeService;
import com.example.receipt.domain.extraction.entity.ReceiptExtractionJob;
import com.example.receipt.domain.extraction.model.ExtractionJobStatus;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.receipt.dto.ReceiptListItemResponse;
import com.example.receipt.domain.receipt.dto.ReceiptListRequest;
import com.example.receipt.domain.receipt.dto.ReceiptPageResponse;
import com.example.receipt.domain.receipt.entity.AuditEvent;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import com.example.receipt.domain.receipt.repository.AuditEventRepository;
import com.example.receipt.domain.receipt.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReceiptQueryService {
    private static final List<ReceiptStatus> REVIEW_PENDING_STATUSES = List.of(
            ReceiptStatus.NEEDS_REVIEW,
            ReceiptStatus.MANUAL_ENTRY,
            ReceiptStatus.NEEDS_RECAPTURE,
            ReceiptStatus.UNREADABLE
    );

    private final CurrentEmployeeService currentEmployeeService;
    private final ReceiptRepository receiptRepository;
    private final AuditEventRepository auditEventRepository;
    private final ReceiptExtractionJobRepository extractionJobRepository;

    public ReceiptPageResponse findMyReceipts(ReceiptListRequest request) {
        request.validate();

        // 요청에 직원 번호를 넣어도 사용하지 않고, 로그인한 직원의 번호로 조회합니다.
        Long employeeId = currentEmployeeService.getCurrentEmployee().id();
        Page<Receipt> receiptPage = receiptRepository.findMyReceipts(
                employeeId,
                request.status(),
                request.fromInclusive(),
                request.toExclusive(),
                request.pageable()
        );

        return createPageResponse(receiptPage);
    }

    @PreAuthorize("hasAnyRole('REVIEWER', 'ADMIN')")
    public ReceiptPageResponse findReviewQueue(ReceiptListRequest request) {
        request.validate();

        Long reviewerId = currentEmployeeService.getCurrentEmployee().id();
        Page<Receipt> receiptPage = receiptRepository.findReviewQueue(
                reviewerId,
                REVIEW_PENDING_STATUSES,
                request.status(),
                request.fromInclusive(),
                request.toExclusive(),
                request.pageable()
        );

        return createPageResponse(receiptPage);
    }

    public Receipt getReceipt(Long receiptId) {
        Employee employee = currentEmployeeService.getCurrentEmployee();
        Receipt receipt = receiptRepository.findById(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(receiptId));

        validateReadPermission(receipt, employee);
        return receipt;
    }

    public ReceiptExtractionJob getExtractionJob(Long receiptId) {
        // 작업 상태를 조회하기 전에도 해당 영수증의 열람 권한을 확인합니다.
        getReceipt(receiptId);

        return extractionJobRepository.findByReceiptId(receiptId)
                .orElseThrow(() -> new ReceiptNotFoundException(receiptId));
    }

    public List<AuditEvent> getAuditEvents(Long receiptId) {
        getReceipt(receiptId);

        return auditEventRepository.findByReceiptIdOrderByOccurredAtAsc(receiptId);
    }

    private void validateReadPermission(Receipt receipt, Employee employee) {
        if (receipt.ownerEmployeeId() == null) {
            if (employee.role() != EmployeeRole.ADMIN) {
                throw new ReceiptNotFoundException(receipt.id());
            }
            return;
        }

        // 일반 직원은 본인이 제출한 영수증만 조회할 수 있습니다.
        if (employee.role() == EmployeeRole.EMPLOYEE
                && !employee.id().equals(receipt.ownerEmployeeId())) {
            throw new ReceiptNotFoundException(receipt.id());
        }
    }

    private ReceiptPageResponse createPageResponse(Page<Receipt> receiptPage) {
        List<Receipt> receipts = receiptPage.getContent();
        Map<Long, ExtractionJobStatus> jobStatusByReceiptId = findJobStatuses(receipts);
        List<ReceiptListItemResponse> content = new ArrayList<>();

        for (Receipt receipt : receipts) {
            ExtractionJobStatus jobStatus = jobStatusByReceiptId.get(receipt.id());
            ReceiptListItemResponse item = ReceiptListItemResponse.from(receipt, jobStatus);
            content.add(item);
        }

        return new ReceiptPageResponse(
                content,
                receiptPage.getNumber(),
                receiptPage.getSize(),
                receiptPage.getTotalElements(),
                receiptPage.getTotalPages(),
                receiptPage.hasNext()
        );
    }

    private Map<Long, ExtractionJobStatus> findJobStatuses(List<Receipt> receipts) {
        Map<Long, ExtractionJobStatus> jobStatusByReceiptId = new HashMap<>();
        if (receipts.isEmpty()) {
            return jobStatusByReceiptId;
        }

        List<Long> receiptIds = new ArrayList<>();
        for (Receipt receipt : receipts) {
            receiptIds.add(receipt.id());
        }

        // 영수증마다 조회하지 않고, 현재 페이지의 작업 상태를 한 번에 가져옵니다.
        List<ReceiptExtractionJob> jobs = extractionJobRepository.findByReceiptIdIn(receiptIds);
        for (ReceiptExtractionJob job : jobs) {
            jobStatusByReceiptId.put(job.receiptId(), job.status());
        }

        return jobStatusByReceiptId;
    }
}
