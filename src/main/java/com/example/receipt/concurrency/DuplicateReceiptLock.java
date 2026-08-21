package com.example.receipt.concurrency;

import java.util.function.Supplier;

/** 동일 회사의 동일 영수증 이미지 처리를 한 번에 하나만 실행한다. */
public interface DuplicateReceiptLock {
    <T> T execute(String companyId, String imageSha256, Supplier<T> action);
}
