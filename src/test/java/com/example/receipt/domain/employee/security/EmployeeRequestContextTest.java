package com.example.receipt.domain.employee.security;

import com.example.receipt.domain.employee.entity.Employee;
import com.example.receipt.domain.employee.model.EmployeeRole;
import com.example.receipt.domain.employee.repository.EmployeeRepository;
import com.example.receipt.domain.employee.service.CurrentEmployeeService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmployeeRequestContextTest {
    private final EmployeeRepository employees = mock(EmployeeRepository.class);
    private final EmployeeSessionValidationFilter filter = new EmployeeSessionValidationFilter(employees);
    private final CurrentEmployeeService current = new CurrentEmployeeService(employees);

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void reusesValidatedEmployeeOnlyDuringTheRequest() throws Exception {
        Employee employee = employee(1L, "employee-a");
        authenticate(employee);
        when(employees.findByLoginId("employee-a")).thenReturn(Optional.of(employee));
        MockHttpServletRequest request = request();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(current.getCurrentEmployee()).isSameAs(employee);
            assertThat(current.getCurrentEmployee()).isSameAs(employee);
        });

        verify(employees, times(1)).findByLoginId("employee-a");
        assertThat(EmployeeRequestContext.getEmployee()).isNull();
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void clearsTheEmployeeEvenWhenTheRequestFails() {
        Employee employee = employee(1L, "employee-a");
        authenticate(employee);
        when(employees.findByLoginId("employee-a")).thenReturn(Optional.of(employee));
        MockHttpServletRequest request = request();

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            assertThat(current.getCurrentEmployee()).isSameAs(employee);
            throw new ServletException("downstream failure");
        })).isInstanceOf(ServletException.class);

        assertThat(EmployeeRequestContext.getEmployee()).isNull();
    }

    @Test
    void reloadsAndRejectsAnAccountDisabledBetweenRequests() throws Exception {
        Employee employee = employee(1L, "employee-a");
        authenticate(employee);
        when(employees.findByLoginId("employee-a")).thenReturn(Optional.of(employee));
        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> current.getCurrentEmployee());
        employee.setActive(false);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request(), response, (req, res) -> fail("비활성 계정이 통과했습니다."));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(employees, times(2)).findByLoginId("employee-a");
    }

    @Test
    void refreshesRolesFromTheDatabaseOnTheNextRequest() throws Exception {
        Employee employee = employee(1L, "employee-a");
        authenticate(employee);
        when(employees.findByLoginId("employee-a")).thenReturn(Optional.of(employee));
        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> current.getCurrentEmployee());
        ReflectionTestUtils.setField(employee, "role", EmployeeRole.REVIEWER);

        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) -> {
            assertThat(current.getCurrentEmployee().role()).isEqualTo(EmployeeRole.REVIEWER);
            assertThat(SecurityContextHolder.getContext().getAuthentication().getAuthorities())
                    .extracting("authority").containsExactly("ROLE_REVIEWER");
        });

        verify(employees, times(2)).findByLoginId("employee-a");
    }

    @Test
    void doesNotShareEmployeeBetweenRequestsOnTheSameThread() throws Exception {
        Employee first = employee(1L, "employee-a");
        Employee second = employee(2L, "employee-b");
        when(employees.findByLoginId("employee-a")).thenReturn(Optional.of(first));
        when(employees.findByLoginId("employee-b")).thenReturn(Optional.of(second));
        authenticate(first);
        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) ->
                assertThat(current.getCurrentEmployee()).isSameAs(first));
        authenticate(second);
        filter.doFilter(request(), new MockHttpServletResponse(), (req, res) ->
                assertThat(current.getCurrentEmployee()).isSameAs(second));

        verify(employees).findByLoginId("employee-a");
        verify(employees).findByLoginId("employee-b");
    }

    @Test
    void rejectsACachedEmployeeThatDoesNotMatchThePrincipal() {
        authenticate(employee(1L, "employee-a"));
        EmployeeRequestContext.store(request(), employee(2L, "employee-b"));

        assertThatThrownBy(current::getCurrentEmployee).isInstanceOf(InsufficientAuthenticationException.class);
        verifyNoInteractions(employees);
    }

    @Test
    void stillRequiresAuthenticationWhenAnEmployeeIsCached() {
        EmployeeRequestContext.store(request(), employee(1L, "employee-a"));

        assertThatThrownBy(current::getCurrentEmployee).isInstanceOf(InsufficientAuthenticationException.class);
        verifyNoInteractions(employees);
    }

    @Test
    void supportsDirectServiceCallsWithoutAnHttpRequest() {
        Employee employee = employee(1L, "employee-a");
        authenticate(employee);
        when(employees.findByLoginId("employee-a")).thenReturn(Optional.of(employee));

        assertThat(current.getCurrentEmployee()).isSameAs(employee);
        verify(employees).findByLoginId("employee-a");
    }

    private MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        return request;
    }

    private Employee employee(Long id, String loginId) {
        Employee employee = new Employee(loginId, loginId, EmployeeRole.EMPLOYEE);
        employee.setPassword("test-password-hash");
        ReflectionTestUtils.setField(employee, "id", id);
        return employee;
    }

    private void authenticate(Employee employee) {
        EmployeePrincipal principal = EmployeePrincipal.forSession(employee);
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
    }
}
