package com.example.receipt.domain.employee.service;

import java.nio.file.*;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "receipt.bootstrap.enabled", havingValue = "true")
public class AdminBootstrap implements ApplicationRunner {
    private final EmployeeService employees;

    private final ConfigurableApplicationContext context;

    private final String loginId;

    private final Path passwordFile;

    public AdminBootstrap(EmployeeService employees, ConfigurableApplicationContext context,
                          @Value("${receipt.bootstrap.login-id}") String loginId,
                          @Value("${receipt.bootstrap.password-file}") String passwordFile) {
        this.employees = employees;
        this.context = context;
        this.loginId = loginId;
        this.passwordFile = Path.of(passwordFile);
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (context instanceof ServletWebServerApplicationContext)
            throw new IllegalStateException("관리자 준비는 web-application-type=none으로 실행하세요.");

        var permissions = Files.getPosixFilePermissions(passwordFile);

        if (permissions.stream().anyMatch(p -> p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")))
            throw new IllegalArgumentException("비밀번호 파일은 소유자만 접근할 수 있어야 합니다 (chmod 600).");

        employees.bootstrap(loginId, Files.readString(passwordFile));
        context.close();
    }
}
