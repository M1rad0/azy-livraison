package com.azylivraison.importexport.api;

import java.util.List;

public record CsvImportResponse<T>(
        boolean valid,
        CsvImportType importType,
        String filename,
        String delimiter,
        int importedCount,
        List<T> items,
        List<CsvImportError> errors) {

    public static <T> CsvImportResponse<T> success(
            CsvImportType importType,
            String filename,
            char delimiter,
            List<T> items) {
        return new CsvImportResponse<>(
                true,
                importType,
                filename,
                Character.toString(delimiter),
                items.size(),
                List.copyOf(items),
                List.of());
    }

    public static CsvImportResponse<Object> failure(CsvImportException exception) {
        return new CsvImportResponse<>(
                false,
                exception.getImportType(),
                exception.getFilename(),
                exception.getDelimiter() == null ? null : Character.toString(exception.getDelimiter()),
                0,
                List.of(),
                exception.getErrors());
    }
}
