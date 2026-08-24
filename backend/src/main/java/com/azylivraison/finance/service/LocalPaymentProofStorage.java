package com.azylivraison.finance.service;

import static com.azylivraison.finance.domain.FinanceModels.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class LocalPaymentProofStorage implements PaymentProofStorage {

    private static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024;
    private static final Map<String, String> EXTENSIONS = Map.of(
            "application/pdf", ".pdf",
            "image/jpeg", ".jpg",
            "image/png", ".png",
            "image/webp", ".webp");

    private final Path root;
    private final long maxBytes;

    public LocalPaymentProofStorage(
            @Value("${app.finance.proofs.storage-path:${PAYMENT_PROOF_STORAGE_PATH:}}") String configuredPath,
            @Value("${app.finance.proofs.max-file-size-mb:${PAYMENT_PROOF_MAX_FILE_SIZE_MB:10}}") long maxSizeMb) {
        String path = configuredPath == null || configuredPath.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "azy-livraison", "payment-proofs").toString()
                : configuredPath.trim();
        this.root = Path.of(path).toAbsolutePath().normalize();
        this.maxBytes = maxSizeMb > 0
                ? Math.multiplyExact(maxSizeMb, 1024L * 1024L)
                : DEFAULT_MAX_BYTES;
    }

    @Override
    public ProofMetadata store(MultipartFile file) {
        if (file == null || file.isEmpty()) return null;
        if (file.getSize() <= 0 || file.getSize() > maxBytes) {
            throw new FinanceInputException("Le justificatif doit peser au maximum 10 Mo.");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (IOException exception) {
            throw new ProofStorageException("Le justificatif n'a pas pu être lu.", exception);
        }
        if (content.length == 0 || content.length > maxBytes) {
            throw new FinanceInputException("Le justificatif doit peser au maximum 10 Mo.");
        }
        String contentType = detectContentType(content);
        String extension = EXTENSIONS.get(contentType);
        String storageKey = UUID.randomUUID() + extension;
        String filename = safeFilename(file.getOriginalFilename(), extension);
        Path target = safeResolve(storageKey);
        Path temporary = safeResolve(storageKey + ".tmp-" + UUID.randomUUID());
        try {
            Files.createDirectories(root);
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target);
            }
        } catch (IOException exception) {
            deletePathQuietly(temporary);
            deletePathQuietly(target);
            throw new ProofStorageException("Le justificatif n'a pas pu être enregistré.", exception);
        }
        return new ProofMetadata(
                storageKey, filename, contentType, content.length, sha256(content));
    }

    @Override
    public ProofDownload load(ProofMetadata metadata) {
        if (metadata == null) {
            throw new FinanceNotFoundException("Aucun justificatif n'est associé à ce versement.");
        }
        Path path = safeResolve(metadata.storageKey());
        try {
            if (!Files.isRegularFile(path)) {
                throw new FinanceNotFoundException("Le justificatif associé est introuvable.");
            }
            byte[] content = Files.readAllBytes(path);
            if (content.length != metadata.sizeBytes() || !sha256(content).equals(metadata.sha256())) {
                throw new ProofStorageException("L'intégrité du justificatif ne peut pas être confirmée.");
            }
            return new ProofDownload(content, metadata.contentType(), metadata.originalFilename());
        } catch (FinanceNotFoundException | ProofStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new ProofStorageException("Le justificatif n'a pas pu être lu.", exception);
        }
    }

    @Override
    public void deleteQuietly(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) return;
        try {
            deletePathQuietly(safeResolve(storageKey));
        } catch (RuntimeException ignored) {
            // Best-effort rollback cleanup. A later maintenance task can remove orphans.
        }
    }

    private String detectContentType(byte[] content) {
        if (startsWith(content, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return "application/pdf";
        if (content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff) return "image/jpeg";
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (startsWith(content, png)) return "image/png";
        if (content.length >= 12
                && new String(content, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
                && new String(content, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")) {
            return "image/webp";
        }
        throw new FinanceInputException("Le justificatif doit être une image PNG, JPEG, WebP ou un PDF valide.");
    }

    private boolean startsWith(byte[] content, byte[] prefix) {
        if (content.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) {
            if (content[index] != prefix[index]) return false;
        }
        return true;
    }

    private Path safeResolve(String storageKey) {
        if (storageKey == null || storageKey.isBlank()
                || storageKey.contains("/") || storageKey.contains("\\")) {
            throw new ProofStorageException("La clé du justificatif est invalide.");
        }
        Path resolved = root.resolve(storageKey).normalize();
        if (!resolved.startsWith(root)) {
            throw new ProofStorageException("La clé du justificatif est invalide.");
        }
        return resolved;
    }

    private String safeFilename(String originalFilename, String extension) {
        String filename = originalFilename == null ? "justificatif" + extension : originalFilename;
        filename = filename.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).trim();
        filename = filename.replaceAll("[\\p{Cntrl}]", "");
        if (filename.isBlank()) filename = "justificatif" + extension;
        String lower = filename.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(extension)) filename += extension;
        if (filename.length() > 255) {
            filename = filename.substring(0, 255 - extension.length()) + extension;
        }
        return filename;
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private void deletePathQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Best effort only.
        }
    }
}
