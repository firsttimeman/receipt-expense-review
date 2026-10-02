package com.example.receipt.domain.employee.controller;
import com.example.receipt.domain.employee.dto.*;
import com.example.receipt.domain.employee.service.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {
    private final EmployeeService service;
    private final CurrentEmployee current;
    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) { return Map.of("headerName", token.getHeaderName(), "token", token.getToken()); }
    @PostMapping("/password") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@Valid @RequestBody SetPasswordRequest request) { service.setPassword(request); }
    @GetMapping("/me") public EmployeeResponse me() { return EmployeeResponse.from(current.require()); }
}
