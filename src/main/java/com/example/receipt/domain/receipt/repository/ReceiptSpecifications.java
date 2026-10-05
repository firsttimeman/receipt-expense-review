package com.example.receipt.domain.receipt.repository;

import com.example.receipt.domain.receipt.entity.Receipt;
import com.example.receipt.domain.receipt.model.ReceiptStatus;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Set;

public final class ReceiptSpecifications {
    private static final Set<ReceiptStatus> REVIEW_PENDING = Set.of(
            ReceiptStatus.NEEDS_REVIEW, ReceiptStatus.MANUAL_ENTRY,
            ReceiptStatus.NEEDS_RECAPTURE, ReceiptStatus.UNREADABLE);

    private ReceiptSpecifications() {
    }

    public static Specification<Receipt> ownedBy(Long employeeId) {
        return (root, query, builder) -> builder.equal(root.get("ownerEmployeeId"), employeeId);
    }

    public static Specification<Receipt> awaitingReviewBy(Long reviewerId) {
        return (root, query, builder) -> builder.and(
                builder.isNotNull(root.get("ownerEmployeeId")),
                builder.notEqual(root.get("ownerEmployeeId"), reviewerId),
                root.get("status").in(REVIEW_PENDING));
    }

    public static Specification<Receipt> withStatus(ReceiptStatus status) {
        return (root, query, builder) -> status == null
                ? builder.conjunction() : builder.equal(root.get("status"), status);
    }

    public static Specification<Receipt> submittedBetween(Instant from, Instant to) {
        return (root, query, builder) -> builder.and(
                from == null ? builder.conjunction() : builder.greaterThanOrEqualTo(root.get("createdAt"), from),
                to == null ? builder.conjunction() : builder.lessThan(root.get("createdAt"), to));
    }
}
