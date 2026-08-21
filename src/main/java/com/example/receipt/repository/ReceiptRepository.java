package com.example.receipt.repository;

import com.example.receipt.entity.Receipt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    Optional<Receipt> findByCompanyIdAndImageSha256(String companyId, String imageSha256);
}
