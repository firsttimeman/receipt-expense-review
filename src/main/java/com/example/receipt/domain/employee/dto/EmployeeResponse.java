package com.example.receipt.domain.employee.dto;
import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
public record EmployeeResponse(Long id, String loginId, String name, EmployeeRole role, boolean active) {
    public static EmployeeResponse from(Employee e) {
        return new EmployeeResponse(e.id(), e.loginId(), e.name(), e.role(), e.active());
    }
}
