package com.azylivraison.finance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import com.azylivraison.finance.domain.FinanceModels.ProofMetadata;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

class LocalPaymentProofStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void storesAndReloadsAProofFromItsOpaqueKey() {
        LocalPaymentProofStorage storage = new LocalPaymentProofStorage(
                temporaryDirectory.toString(), 10);
        byte[] content = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 1, 2, 3};

        ProofMetadata metadata = storage.store(new MockMultipartFile(
                "proof", "capture.png", "application/octet-stream", content));

        assertThat(metadata.storageKey()).doesNotContain("capture").endsWith(".png");
        assertThat(metadata.contentType()).isEqualTo("image/png");
        assertThat(metadata.sha256()).hasSize(64);
        assertThat(storage.load(metadata).content()).isEqualTo(content);
    }

    @Test
    void detectsTheContentInsteadOfTrustingTheDeclaredMimeType() {
        LocalPaymentProofStorage storage = new LocalPaymentProofStorage(
                temporaryDirectory.toString(), 10);

        assertThatThrownBy(() -> storage.store(new MockMultipartFile(
                "proof", "faux.pdf", "application/pdf", "not a pdf".getBytes())))
                .isInstanceOf(FinanceInputException.class)
                .hasMessageContaining("PNG, JPEG, WebP ou un PDF");
    }

    @Test
    void refusesStorageKeysThatCouldEscapeTheConfiguredDirectory() {
        LocalPaymentProofStorage storage = new LocalPaymentProofStorage(
                temporaryDirectory.toString(), 10);
        ProofMetadata malicious = new ProofMetadata(
                "../secret.pdf", "secret.pdf", "application/pdf", 1, "0".repeat(64));

        assertThatThrownBy(() -> storage.load(malicious))
                .isInstanceOf(ProofStorageException.class)
                .hasMessageContaining("clé du justificatif");
    }
}
