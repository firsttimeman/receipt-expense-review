package com.example.receipt.domain.employee.controller;

import com.example.receipt.domain.employee.dto.EmployeeResponse;
import com.example.receipt.domain.employee.dto.LoginRequest;
import com.example.receipt.domain.employee.dto.SetPasswordRequest;
import com.example.receipt.domain.employee.service.CurrentEmployee;
import com.example.receipt.domain.employee.service.EmployeeService;
import com.example.receipt.domain.employee.service.LoginService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@ConditionalOnWebApplication
public class AuthController {
    private final EmployeeService service;

    private final CurrentEmployee current;

    private final LoginService loginService;

    @GetMapping("/csrf")
    public Map<String, String> csrf(CsrfToken token) {
        return Map.of("headerName", token.getHeaderName(), "token", token.getToken());
    }

    @PostMapping(value = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void login(@Valid @RequestBody LoginRequest credentials,
                      HttpServletRequest request, HttpServletResponse response) {
        loginService.login(credentials, request, response);
    }

    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void password(@Valid @RequestBody SetPasswordRequest request) {
        service.setPassword(request);
    }

    @GetMapping("/me")
    public EmployeeResponse me() {
        return EmployeeResponse.from(current.require());
    }
}
