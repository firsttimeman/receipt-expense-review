package com.example.receipt.domain.receipt.repository;

import com.example.receipt.domain.receipt.entity.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.Optional;

public interface ReceiptRepository extends JpaRepository<Receipt, Long>, JpaSpecificationExecutor<Receipt> {
    Optional<Receipt> findByCompanyIdAndImageSha256(String companyId, String imageSha256);
}
