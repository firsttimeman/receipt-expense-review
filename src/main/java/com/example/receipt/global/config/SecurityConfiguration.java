package com.example.receipt.global.config;

import com.example.receipt.domain.employee.repository.EmployeeRepository;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableMethodSecurity
@org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
public class SecurityConfiguration {
    @Bean UserDetailsService userDetailsService(EmployeeRepository employees) {
        return login -> employees.findByLoginId(login).filter(e -> e.passwordHash() != null)
                .map(e -> User.withUsername(e.loginId()).password(e.passwordHash()).roles(e.role().name()).disabled(!e.active()).build())
                .orElseThrow(() -> new UsernameNotFoundException("인증할 수 없습니다."));
    }
    @Bean SecurityFilterChain security(HttpSecurity http, EmployeeRepository employees) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/csrf", "/api/auth/login", "/api/auth/password", "/actuator/health", "/error").permitAll()
                .requestMatchers("/api/employees/**", "/actuator/**").hasRole("ADMIN")
                .anyRequest().authenticated())
            .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                .usernameParameter("loginId").passwordParameter("password")
                .successHandler((req, res, auth) -> res.setStatus(204))
                .failureHandler((req, res, ex) -> res.setStatus(401)).permitAll())
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .invalidateHttpSession(true).deleteCookies("JSESSIONID")
                .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)))
            .requestCache(cache -> cache.disable())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((req, res, ex) -> res.setStatus(401))
                .accessDeniedHandler((req, res, ex) -> res.setStatus(403)))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                        throws ServletException, IOException {
                    var auth = SecurityContextHolder.getContext().getAuthentication();
                    if (auth != null && auth.isAuthenticated() && !(auth instanceof org.springframework.security.authentication.AnonymousAuthenticationToken)) {
                        var employee = employees.findByLoginId(auth.getName());
                        if (employee.isEmpty() || !employee.get().active() || employee.get().passwordHash() == null) {
                            SecurityContextHolder.clearContext();
                            if (req.getSession(false) != null) req.getSession(false).invalidate();
                            res.setStatus(401); return;
                        }
                        // Re-read the role on every request; stale sessions cannot retain elevated privileges.
                        var e = employee.get();
                        var principal = User.withUsername(e.loginId()).password("").roles(e.role().name()).build();
                        SecurityContextHolder.getContext().setAuthentication(
                            UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
                    }
                    chain.doFilter(req, res);
                }
            }, AuthorizationFilter.class);
        return http.build();
    }
}
