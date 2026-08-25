package com.example.receipt.storage;

public interface ReceiptImageStorage {
    String store(String companyId, String imageSha256, byte[] bytes);

    byte[] load(String storageKey);
}
