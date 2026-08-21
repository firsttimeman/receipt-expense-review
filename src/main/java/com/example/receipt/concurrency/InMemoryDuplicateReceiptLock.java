package com.example.receipt.concurrency;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/** 외부 Redis 없이 빠르게 실행하는 test 프로필 전용 대체 구현이다. */
@Component
@Profile("test")
public class InMemoryDuplicateReceiptLock implements DuplicateReceiptLock {
    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T execute(String companyId, String imageSha256, Supplier<T> action) {
        String key = companyId + ":" + imageSha256;
        ReentrantLock lock = locks.computeIfAbsent(key, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads()) {
                locks.remove(key, lock);
            }
        }
    }
}
