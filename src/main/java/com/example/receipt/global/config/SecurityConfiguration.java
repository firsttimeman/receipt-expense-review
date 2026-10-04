package com.example.receipt.global.config;

import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.domain.employee.security.EmployeeSessionValidationFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

@Configuration
@EnableMethodSecurity
@ConditionalOnWebApplication
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain filterChain(
            HttpSecurity http,
            EmployeeRepository employees,
            SecurityContextRepository securityContextRepository,
            CsrfTokenRepository csrfTokenRepository) throws Exception {
        // API 인증 정보는 세션에 저장하고, Spring Session을 통해 Redis에서 공유합니다.
        http
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/api/auth/csrf",
                                "/api/auth/login",
                                "/api/auth/password",
                                "/actuator/health",
                                "/error"
                        ).permitAll()
                        .requestMatchers("/api/employees/**", "/actuator/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository))
                .securityContext(context -> context
                        .securityContextRepository(securityContextRepository)
                        .requireExplicitSave(true))
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden))
                .logout(logout -> logout
                        .logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true)
                        .logoutSuccessHandler(this::loggedOut))
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                // 서블릿 필터로 중복 등록하지 않고 Spring Security 필터 체인에서만 사용합니다.
                .addFilterBefore(new EmployeeSessionValidationFilter(employees), AuthorizationFilter.class);

        return http.build();
    }

    private void unauthorized(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception) {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
    }

    private void forbidden(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception) {
        response.setStatus(HttpStatus.FORBIDDEN.value());
    }

    private void loggedOut(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        response.setStatus(HttpStatus.NO_CONTENT.value());
    }
}
