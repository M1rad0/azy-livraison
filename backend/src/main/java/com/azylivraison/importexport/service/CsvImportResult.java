package com.azylivraison.importexport.service;

import java.util.List;

import com.azylivraison.importexport.api.CsvImportType;

public record CsvImportResult<T>(
        CsvImportType importType,
        String filename,
        char delimiter,
        List<T> items) {
}
