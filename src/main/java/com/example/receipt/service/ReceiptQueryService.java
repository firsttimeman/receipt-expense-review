package com.example.receipt.service;

import com.example.receipt.entity.AuditEvent;
import com.example.receipt.entity.Receipt;
import com.example.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.repository.AuditEventRepository;
import com.example.receipt.repository.ReceiptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReceiptQueryService {
    private final ReceiptRepository receiptRepository;
    private final AuditEventRepository auditRepository;

    public Receipt get(Long id) {
        return receiptRepository.findById(id).orElseThrow(() -> new ReceiptNotFoundException(id));
    }

    public List<AuditEvent> auditLog(Long id) {
        if (!receiptRepository.existsById(id)) throw new ReceiptNotFoundException(id);
        return auditRepository.findByReceiptIdOrderByOccurredAtAsc(id);
    }
}
