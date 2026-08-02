package com.azylivraison.importexport.api;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

@RestControllerAdvice(basePackageClasses = CsvImportController.class)
public class CsvImportExceptionHandler {

    @ExceptionHandler(CsvImportException.class)
    public ResponseEntity<CsvImportResponse<Object>> handleImportValidation(CsvImportException exception) {
        HttpStatus status = exception.getErrors().stream()
                .anyMatch(error -> "FILE_TOO_LARGE".equals(error.code()))
                        ? HttpStatus.PAYLOAD_TOO_LARGE
                        : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(CsvImportResponse.failure(exception));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<CsvImportResponse<Object>> handleOversizedFile(
            MaxUploadSizeExceededException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.PAYLOAD_TOO_LARGE,
                request,
                "FILE_TOO_LARGE",
                "Le fichier dépasse la taille maximale autorisée.");
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<CsvImportResponse<Object>> handleMissingFile(
            MissingServletRequestPartException exception,
            HttpServletRequest request) {
        return response(
                HttpStatus.BAD_REQUEST,
                request,
                "MISSING_FILE",
                "Ajoutez le fichier dans le champ multipart 'file'.");
    }

    private ResponseEntity<CsvImportResponse<Object>> response(
            HttpStatus status,
            HttpServletRequest request,
            String code,
            String message) {
        CsvImportException exception = new CsvImportException(
                importType(request),
                "import.csv",
                null,
                List.of(new CsvImportError(null, "file", code, message)));
        return ResponseEntity.status(status).body(CsvImportResponse.failure(exception));
    }

    private CsvImportType importType(HttpServletRequest request) {
        return request.getRequestURI().contains("/couriers/")
                ? CsvImportType.COURIERS
                : CsvImportType.PACKAGES;
    }
}
