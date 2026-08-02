package com.azylivraison.importexport.api;

public record CsvImportError(
        Integer line,
        String field,
        String code,
        String message) {
}
