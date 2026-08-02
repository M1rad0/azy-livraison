package com.azylivraison.importexport.api;

import java.util.List;

public class CsvImportException extends RuntimeException {

    private final CsvImportType importType;
    private final String filename;
    private final Character delimiter;
    private final List<CsvImportError> errors;

    public CsvImportException(
            CsvImportType importType,
            String filename,
            Character delimiter,
            List<CsvImportError> errors) {
        super("Le fichier CSV contient des erreurs.");
        this.importType = importType;
        this.filename = filename;
        this.delimiter = delimiter;
        this.errors = List.copyOf(errors);
    }

    public CsvImportType getImportType() {
        return importType;
    }

    public String getFilename() {
        return filename;
    }

    public Character getDelimiter() {
        return delimiter;
    }

    public List<CsvImportError> getErrors() {
        return errors;
    }
}
