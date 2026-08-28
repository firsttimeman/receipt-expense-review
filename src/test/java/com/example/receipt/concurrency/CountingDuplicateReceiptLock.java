package com.example.receipt.concurrency;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/** 중복 업로드가 실제 락 경로에 진입한 횟수를 기록하는 테스트 대역이다. */
public final class CountingDuplicateReceiptLock implements DuplicateReceiptLock {
    private final DuplicateReceiptLock delegate;
    private final AtomicInteger invocationCount = new AtomicInteger();

    public CountingDuplicateReceiptLock(DuplicateReceiptLock delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> T execute(String companyId, String imageSha256, Supplier<T> action) {
        invocationCount.incrementAndGet();
        return delegate.execute(companyId, imageSha256, action);
    }

    public int invocationCount() {
        return invocationCount.get();
    }

    public void reset() {
        invocationCount.set(0);
    }
}
