package com.example.receipt.concurrency;

import com.example.receipt.exception.ReceiptConflictException;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Component
@Profile("!test")
@RequiredArgsConstructor
public class RedisDuplicateReceiptLock implements DuplicateReceiptLock {
    private static final long LOCK_WAIT_SECONDS = 3;
    private final RedissonClient redissonClient;

    @Override
    public <T> T execute(String companyId, String imageSha256, Supplier<T> action) {
        String lockKey = "receipt:duplicate:%s:%s".formatted(companyId, imageSha256);
        RLock lock = redissonClient.getLock(lockKey);
        boolean acquired;
        try {
            // leaseTime을 지정하지 않아 Redisson watchdog이 작업 중 락 만료를 연장한다.
            acquired = lock.tryLock(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ReceiptConflictException("중복 영수증 처리 대기 중 요청이 중단되었습니다.");
        }

        if (!acquired) {
            throw new ReceiptConflictException("동일한 영수증 이미지가 이미 처리 중입니다.");
        }

        try {
            return action.get();
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
