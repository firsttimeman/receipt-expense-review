package com.example.receipt.domain.employee.repository;

import com.example.receipt.domain.employee.entity.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByLoginId(String loginId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.setupTokenHash = :hash")
    Optional<Employee> findForSetup(String hash);
    @Query(value = "select id from account_bootstrap_lock where id = 1 for update", nativeQuery = true)
    Integer lockBootstrap();
}
