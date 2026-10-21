package com.example.receipt.domain.employee.repository;

import com.example.receipt.domain.employee.entity.Employee;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface EmployeeRepository extends JpaRepository<Employee, Long> {
    Optional<Employee> findByLoginId(String loginId);

    @Query("select e.id from Employee e where e.setupTokenHash = :hash")
    Optional<Long> findIdBySetupTokenHash(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from Employee e where e.id = :id")
    Optional<Employee> findByIdForUpdate(@Param("id") Long id);

    @Query(value = "select id from account_bootstrap_lock where id = 1 for update", nativeQuery = true)
    Integer lockBootstrap();
}
