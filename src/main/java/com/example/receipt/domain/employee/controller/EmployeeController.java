package com.example.receipt.domain.employee.controller;
import com.example.receipt.domain.employee.dto.*;
import com.example.receipt.domain.employee.service.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/employees")
@RequiredArgsConstructor
public class EmployeeController {
    private final EmployeeService service;
    private final CurrentEmployee current;
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    public EmployeeService.IssuedEmployee issue(@Valid @RequestBody IssueEmployeeRequest request) { return service.issue(request); }
    public record ActiveRequest(@NotNull Boolean active) {}
    @PatchMapping("/{id}/active")
    public EmployeeResponse active(@PathVariable Long id, @Valid @RequestBody ActiveRequest request) {
        return service.active(id, request.active(), current.require().id());
    }
}
