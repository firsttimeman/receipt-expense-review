package com.example.receipt;

import com.example.receipt.domain.employee.controller.AuthController;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.domain.employee.service.CurrentEmployeeService;
import com.example.receipt.domain.employee.service.EmployeeService;
import com.example.receipt.domain.employee.service.LoginService;
import com.example.receipt.global.config.SecurityConfiguration;
import com.example.receipt.global.config.SessionConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 로그인 화면을 열어도 업무 API의 인증과 CSRF 보호는 유지되어야 합니다. */
@WebMvcTest(AuthController.class)
@Import({SecurityConfiguration.class, SessionConfiguration.class})
class LocalFrontendAccessTest {
    @Autowired MockMvc mvc;
    @MockBean EmployeeRepository employees;
    @MockBean EmployeeService employeeService;
    @MockBean CurrentEmployeeService currentEmployeeService;
    @MockBean LoginService loginService;

    @Test
    void homepageIsAccessibleBeforeLogin() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("id=\"login-form\"")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assets/receipt-app.css", "/assets/receipt-app.js"})
    void loginAssetsArePublic(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/auth/me", "/api/receipts", "/api/receipts/review-queue", "/api/employees"})
    void businessApisStillRequireLogin(String path) throws Exception {
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @Test
    void loginStillRequiresCsrfToken() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"loginId\":\"example\",\"password\":\"example-password\"}"))
                .andExpect(status().isForbidden());
    }
}
