package com.example.receipt.global.storage;

import java.io.IOException;
import java.nio.file.*;

public class LocalReceiptImageStorage implements ReceiptImageStorage {
    private final Path root;

    public LocalReceiptImageStorage(String directory) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public String store(String companyId, String imageSha256, byte[] bytes) {
        String storageKey = ReceiptImageKey.create(companyId, imageSha256);
        Path target = resolveSafely(storageKey);
        try {
            Files.createDirectories(target.getParent());
            if (Files.exists(target)) return storageKey;
            Path temporary = Files.createTempFile(target.getParent(), imageSha256, ".tmp");
            try {
                Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING);
                try {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException exception) {
                    Files.move(temporary, target);
                } catch (FileAlreadyExistsException ignored) {
                    // 같은 내용 주소 키를 다른 요청이 먼저 저장한 경우 기존 파일을 재사용한다.
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
            return storageKey;
        } catch (IOException exception) {
            throw new ReceiptImageStorageException("비동기 처리를 위한 영수증 이미지를 저장하지 못했습니다.", exception);
        }
    }

    @Override
    public byte[] load(String storageKey) {
        try {
            return Files.readAllBytes(resolveSafely(storageKey));
        } catch (IOException exception) {
            throw new ReceiptImageStorageException("저장된 영수증 이미지를 읽지 못했습니다.", exception);
        }
    }

    private Path resolveSafely(String storageKey) {
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("허용되지 않은 이미지 저장 키입니다.");
        }
        return resolved;
    }

}
