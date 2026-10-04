package com.example.receipt.domain.receipt.service;

import com.example.receipt.domain.extraction.entity.ReceiptExtractionJob;
import com.example.receipt.domain.extraction.repository.ReceiptExtractionJobRepository;
import com.example.receipt.domain.receipt.entity.AuditEvent;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.domain.receipt.repository.AuditEventRepository;
import com.example.receipt.domain.receipt.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReceiptQueryService {
    private final ReceiptAccess access;
    private final ReceiptRepository receiptRepository;
    private final AuditEventRepository auditRepository;
    private final ReceiptExtractionJobRepository jobRepository;

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
