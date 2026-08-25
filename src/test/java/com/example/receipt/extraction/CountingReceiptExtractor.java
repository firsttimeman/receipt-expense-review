package com.example.receipt.extraction;

import java.util.concurrent.atomic.AtomicInteger;

/** 외부 AI 호출 횟수에 해당하는 추출기 실행 횟수를 기록하는 테스트 대역이다. */
public final class CountingReceiptExtractor implements ReceiptExtractor {
    private final ReceiptExtractor delegate;
    private final AtomicInteger invocationCount = new AtomicInteger();

    public CountingReceiptExtractor(ReceiptExtractor delegate) {
        this.delegate = delegate;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        invocationCount.incrementAndGet();
        return delegate.extract(request);
    }

    public int invocationCount() {
        return invocationCount.get();
    }

    public void reset() {
        invocationCount.set(0);
    }
}
