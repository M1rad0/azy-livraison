package com.azylivraison.finance.service;

import static com.azylivraison.finance.domain.FinanceModels.*;

import org.springframework.web.multipart.MultipartFile;

public interface PaymentProofStorage {

    ProofMetadata store(MultipartFile file);

    ProofDownload load(ProofMetadata metadata);

    void deleteQuietly(String storageKey);
}
