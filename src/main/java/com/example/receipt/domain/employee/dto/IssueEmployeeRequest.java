package com.example.receipt.domain.employee.dto;
import com.example.receipt.domain.employee.model.EmployeeRole;
import jakarta.validation.constraints.*;
public record IssueEmployeeRequest(
        @NotBlank @Pattern(regexp = "[a-z0-9._-]{3,64}") String loginId,
        @NotBlank @Size(max = 100) String name, @NotNull EmployeeRole role) {}
