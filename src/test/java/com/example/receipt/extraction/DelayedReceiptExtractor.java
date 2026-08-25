package com.example.receipt.extraction;

import java.time.Duration;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 지정한 수의 호출이 추출 구간에 함께 진입하도록 대기시킨 뒤 응답을 지연하는 테스트 대역이다.
 * 느린 외부 AI에 동일 이미지 요청이 동시에 몰리는 상황을 결정적으로 재현한다.
 */
public final class DelayedReceiptExtractor implements ReceiptExtractor {
    private final ReceiptExtractor delegate;
    private final CyclicBarrier concurrentCallBarrier;
    private final Duration barrierTimeout;
    private final Duration responseDelay;

    public DelayedReceiptExtractor(ReceiptExtractor delegate, int expectedConcurrentCalls,
                                   Duration barrierTimeout, Duration responseDelay) {
        if (expectedConcurrentCalls <= 0) {
            throw new IllegalArgumentException("동시 호출 수는 1 이상이어야 합니다.");
        }
        this.delegate = delegate;
        this.concurrentCallBarrier = new CyclicBarrier(expectedConcurrentCalls);
        this.barrierTimeout = barrierTimeout;
        this.responseDelay = responseDelay;
    }

    @Override
    public ExtractionResult extract(ExtractionRequest request) {
        awaitConcurrentCalls();
        delayResponse();
        return delegate.extract(request);
    }

    private void awaitConcurrentCalls() {
        try {
            concurrentCallBarrier.await(barrierTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("동시 추출 재현 대기 중 스레드가 중단되었습니다.", exception);
        } catch (BrokenBarrierException | TimeoutException exception) {
            throw new ExtractionException("예상한 수의 동시 추출 호출이 진입하지 않았습니다.", exception);
        }
    }

    private void delayResponse() {
        try {
            TimeUnit.MILLISECONDS.sleep(responseDelay.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ExtractionException("지연 추출 응답 대기 중 스레드가 중단되었습니다.", exception);
        }
    }
}
