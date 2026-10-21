package com.example.receipt.domain.employee.exception;

public class EmployeeSetupConflictException extends RuntimeException {
    public EmployeeSetupConflictException(String message) {
        super(message);
    }
}
