package com.example.receipt.domain.receipt.service;

import com.example.receipt.domain.employee.service.CurrentEmployee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.exception.ReceiptNotFoundException;
import com.example.receipt.domain.receipt.exception.ReceiptConflictException;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class ReceiptAccess {
    private final CurrentEmployee current;
    public void read(Receipt receipt) {
        var employee = current.require();
        if (receipt.ownerEmployeeId() == null) {
            if (employee.role() != EmployeeRole.ADMIN) throw new ReceiptNotFoundException(receipt.id());
        } else if (employee.role() == EmployeeRole.EMPLOYEE && !employee.id().equals(receipt.ownerEmployeeId())) {
            throw new ReceiptNotFoundException(receipt.id());
        }
    }
    public void modify(Receipt receipt) {
        read(receipt);
        if (receipt.ownerEmployeeId() == null) throw new ReceiptConflictException("기존 소유자 없는 영수증은 읽기 전용으로 격리되어 있습니다.");
        if (current.require().role() == EmployeeRole.EMPLOYEE && (receipt.status() == null ||
                !Set.of(ReceiptStatus.NEEDS_REVIEW, ReceiptStatus.NEEDS_RECAPTURE,
                        ReceiptStatus.UNREADABLE, ReceiptStatus.MANUAL_ENTRY).contains(receipt.status())))
            throw new ReceiptConflictException("이 상태의 영수증은 직원이 수정할 수 없습니다.");
    }
    public void review(Receipt receipt) {
        if (current.require().role() == EmployeeRole.EMPLOYEE) throw new AccessDeniedException("검토 권한이 필요합니다.");
        modify(receipt);
    }
}
