package com.example.receipt.domain.employee.service;

import com.example.receipt.domain.employee.dto.LoginRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@ConditionalOnWebApplication
public class LoginService {
    private final AuthenticationManager passwordAuthenticationManager;

    private final SessionAuthenticationStrategy sessionAuthenticationStrategy;

    private final SecurityContextRepository securityContextRepository;

    public void login(LoginRequest credentials, HttpServletRequest request, HttpServletResponse response) {
        try {
            Authentication authentication = passwordAuthenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(
                            credentials.getLoginId(), credentials.getPassword()));

            // 직접 로그인하므로 세션 ID 변경과 기존 CSRF 토큰 폐기도 직접 실행합니다.
            sessionAuthenticationStrategy.onAuthentication(authentication, request, response);

            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);

            // Spring Session이 HttpSession을 Redis에 저장합니다.
            securityContextRepository.saveContext(context, request, response);
        } catch (AuthenticationException exception) {
            SecurityContextHolder.clearContext();
            securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), request, response);
            throw exception;
        }
    }
}
