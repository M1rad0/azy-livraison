package com.azylivraison.importexport.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import com.azylivraison.importexport.api.CsvImportError;
import com.azylivraison.importexport.api.CsvImportException;
import com.azylivraison.importexport.api.CsvImportType;
import com.azylivraison.importexport.config.ImportProperties;
import com.azylivraison.planning.domain.Courier;
import com.azylivraison.planning.domain.DeliveryPackage;
import com.azylivraison.planning.domain.DeliveryTimeWindow;
import com.azylivraison.planning.domain.Location;
import com.azylivraison.planning.domain.VehiclePreset;
import com.azylivraison.planning.domain.VehicleType;
import com.azylivraison.planning.service.VehiclePresetService;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CsvImportService {

    private static final Set<String> PACKAGE_HEADERS = Set.of(
            "id", "label", "weight_kg", "destination_query", "latitude", "longitude", "phone",
            "window_start_at", "window_end_at");
    private static final Set<String> COURIER_HEADERS = Set.of(
            "id", "name", "vehicle_type", "max_weight_kg", "average_speed_kmh", "departure_at",
            "departure_query", "departure_latitude", "departure_longitude");
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "text/csv",
            "application/csv",
            "application/vnd.ms-excel",
            "text/plain",
            "application/octet-stream");
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,99}");
    private static final int MAX_PACKAGES = 2_000;
    private static final int MAX_COURIERS = 200;

    private final ImportProperties properties;
    private final VehiclePresetService vehiclePresetService;

    public CsvImportService(ImportProperties properties, VehiclePresetService vehiclePresetService) {
        this.properties = properties;
        this.vehiclePresetService = vehiclePresetService;
    }

    public CsvImportResult<DeliveryPackage> importPackages(MultipartFile file) {
        ParsedCsv csv = parse(file, CsvImportType.PACKAGES, PACKAGE_HEADERS, MAX_PACKAGES);
        List<CsvImportError> errors = new ArrayList<>();
        List<DeliveryPackage> packages = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();

        for (CSVRecord record : csv.records()) {
            int line = logicalLine(record);
            int errorsBeforeRow = errors.size();

            String id = requiredText(record, line, "id", 100, errors);
            if (id != null && !IDENTIFIER_PATTERN.matcher(id).matches()) {
                errors.add(error(line, "id", "INVALID_IDENTIFIER",
                        "Utilisez uniquement lettres, chiffres, _ ou -, sans espace."));
            } else if (id != null && !identifiers.add(id)) {
                errors.add(error(line, "id", "DUPLICATE_ID",
                        "Cet identifiant de colis apparaît plusieurs fois."));
            }

            String label = requiredText(record, line, "label", 200, errors);
            BigDecimal weight = decimal(record, line, "weight_kg", false, errors);
            requirePositive(weight, line, "weight_kg", errors);
            String destinationQuery = optionalText(record, line, "destination_query", 500, errors);
            Double latitude = coordinate(record, line, "latitude", -90, 90, errors);
            Double longitude = coordinate(record, line, "longitude", -180, 180, errors);
            String phone = optionalText(record, line, "phone", 50, errors);
            OffsetDateTime windowStart = offsetDateTime(record, line, "window_start_at", errors);
            OffsetDateTime windowEnd = offsetDateTime(record, line, "window_end_at", errors);

            if ((windowStart == null) != (windowEnd == null)) {
                errors.add(error(line, "window_start_at,window_end_at", "INCOMPLETE_TIME_WINDOW",
                        "Le début et la fin du créneau doivent être fournis ensemble."));
            } else if (windowStart != null && !windowEnd.isAfter(windowStart)) {
                errors.add(error(line, "window_end_at", "INVALID_TIME_WINDOW",
                        "La fin du créneau doit être postérieure à son début."));
            }

            boolean latitudeProvided = !value(record, "latitude").isBlank();
            boolean longitudeProvided = !value(record, "longitude").isBlank();
            if (latitudeProvided != longitudeProvided) {
                errors.add(error(line, "latitude,longitude", "INCOMPLETE_COORDINATES",
                        "La latitude et la longitude doivent être fournies ensemble."));
            } else if (!latitudeProvided && destinationQuery == null) {
                errors.add(error(line, "destination_query", "DESTINATION_REQUIRED",
                        "Fournissez une destination textuelle ou les deux coordonnées."));
            }

            if (errors.size() == errorsBeforeRow) {
                packages.add(new DeliveryPackage(
                        id,
                        label,
                        weight,
                        phone,
                        new Location(null, destinationQuery, latitude, longitude),
                        windowStart == null ? null : new DeliveryTimeWindow(windowStart, windowEnd)));
            }
        }

        throwIfErrors(csv, errors);
        return new CsvImportResult<>(CsvImportType.PACKAGES, csv.filename(), csv.delimiter(), List.copyOf(packages));
    }

    public CsvImportResult<Courier> importCouriers(MultipartFile file) {
        ParsedCsv csv = parse(file, CsvImportType.COURIERS, COURIER_HEADERS, MAX_COURIERS);
        List<CsvImportError> errors = new ArrayList<>();
        List<Courier> couriers = new ArrayList<>();
        Set<String> identifiers = new HashSet<>();

        for (CSVRecord record : csv.records()) {
            int line = logicalLine(record);
            int errorsBeforeRow = errors.size();

            String id = requiredText(record, line, "id", 100, errors);
            if (id != null && !IDENTIFIER_PATTERN.matcher(id).matches()) {
                errors.add(error(line, "id", "INVALID_IDENTIFIER",
                        "Utilisez uniquement lettres, chiffres, _ ou -, sans espace."));
            } else if (id != null && !identifiers.add(id)) {
                errors.add(error(line, "id", "DUPLICATE_ID",
                        "Cet identifiant de livreur apparaît plusieurs fois."));
            }

            String name = requiredText(record, line, "name", 200, errors);
            VehicleType vehicleType = vehicleType(record, line, errors);
            BigDecimal maxWeight = decimal(record, line, "max_weight_kg", true, errors);
            BigDecimal averageSpeed = decimal(record, line, "average_speed_kmh", true, errors);
            requirePositive(maxWeight, line, "max_weight_kg", errors);
            requirePositive(averageSpeed, line, "average_speed_kmh", errors);
            OffsetDateTime departureAt = offsetDateTime(record, line, "departure_at", errors);
            String departureQuery = optionalText(record, line, "departure_query", 500, errors);
            Double departureLatitude = coordinate(record, line, "departure_latitude", -90, 90, errors);
            Double departureLongitude = coordinate(record, line, "departure_longitude", -180, 180, errors);
            boolean departureLatitudeProvided = !value(record, "departure_latitude").isBlank();
            boolean departureLongitudeProvided = !value(record, "departure_longitude").isBlank();

            if (departureLatitudeProvided != departureLongitudeProvided) {
                errors.add(error(line, "departure_latitude,departure_longitude", "INCOMPLETE_COORDINATES",
                        "La latitude et la longitude de départ doivent être fournies ensemble."));
            }

            if (errors.size() == errorsBeforeRow) {
                VehiclePreset preset = vehiclePresetService.get(vehicleType);
                couriers.add(new Courier(
                        id,
                        name,
                        vehicleType,
                        maxWeight == null ? preset.maxWeightKg() : maxWeight,
                        averageSpeed == null ? preset.averageSpeedKmh() : averageSpeed,
                        departureAt,
                        departureQuery == null && !departureLatitudeProvided
                                ? null
                                : new Location(null, departureQuery, departureLatitude, departureLongitude)));
            }
        }

        throwIfErrors(csv, errors);
        return new CsvImportResult<>(CsvImportType.COURIERS, csv.filename(), csv.delimiter(), List.copyOf(couriers));
    }

    private ParsedCsv parse(
            MultipartFile file,
            CsvImportType importType,
            Set<String> expectedHeaders,
            int maxRows) {
        String filename = safeFilename(file == null ? null : file.getOriginalFilename());
        List<CsvImportError> fileErrors = validateFile(file, filename);
        if (!fileErrors.isEmpty()) {
            throw new CsvImportException(importType, filename, null, fileErrors);
        }

        String content;
        try {
            content = decodeUtf8(file.getBytes());
        } catch (CharacterCodingException exception) {
            throw new CsvImportException(importType, filename, null, List.of(
                    error(null, "file", "INVALID_ENCODING", "Le fichier doit être encodé en UTF-8.")));
        } catch (IOException exception) {
            throw new CsvImportException(importType, filename, null, List.of(
                    error(null, "file", "FILE_READ_ERROR", "Le fichier n'a pas pu être lu.")));
        }

        if (content.startsWith("\uFEFF")) {
            content = content.substring(1);
        }
        if (content.indexOf('\0') >= 0) {
            throw new CsvImportException(importType, filename, null, List.of(
                    error(null, "file", "INVALID_CONTENT", "Le fichier contient des données binaires.")));
        }

        Character delimiter = detectDelimiter(content);
        if (delimiter == null) {
            throw new CsvImportException(importType, filename, null, List.of(
                    error(1, "header", "DELIMITER_NOT_DETECTED",
                            "Utilisez le séparateur point-virgule ou virgule.")));
        }

        try (CSVParser parser = CSVParser.parse(content, CSVFormat.DEFAULT.builder()
                .setDelimiter(delimiter)
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .get())) {
            List<CsvImportError> headerErrors = validateHeaders(parser.getHeaderNames(), expectedHeaders);
            if (!headerErrors.isEmpty()) {
                throw new CsvImportException(importType, filename, delimiter, headerErrors);
            }

            List<CSVRecord> records = parser.getRecords();
            if (records.isEmpty()) {
                throw new CsvImportException(importType, filename, delimiter, List.of(
                        error(2, "file", "EMPTY_IMPORT", "Le fichier ne contient aucune ligne de données.")));
            }
            if (records.size() > maxRows) {
                throw new CsvImportException(importType, filename, delimiter, List.of(
                        error(maxRows + 2, "file", "TOO_MANY_ROWS",
                                "Le fichier dépasse la limite de " + maxRows + " lignes.")));
            }
            for (CSVRecord record : records) {
                if (!record.isConsistent()) {
                    throw new CsvImportException(importType, filename, delimiter, List.of(
                            error(logicalLine(record), "row", "INCONSISTENT_COLUMNS",
                                    "Le nombre de colonnes ne correspond pas à l'en-tête.")));
                }
            }
            return new ParsedCsv(filename, delimiter, List.copyOf(records), importType);
        } catch (CsvImportException exception) {
            throw exception;
        } catch (IOException | UncheckedIOException | IllegalArgumentException exception) {
            throw new CsvImportException(importType, filename, delimiter, List.of(
                    error(null, "file", "MALFORMED_CSV",
                            "Le fichier CSV est mal formé ou contient des guillemets invalides.")));
        }
    }

    private List<CsvImportError> validateFile(MultipartFile file, String filename) {
        List<CsvImportError> errors = new ArrayList<>();
        if (file == null || file.isEmpty()) {
            errors.add(error(null, "file", "EMPTY_FILE", "Sélectionnez un fichier CSV non vide."));
            return errors;
        }
        if (!filename.toLowerCase(Locale.ROOT).endsWith(".csv")) {
            errors.add(error(null, "file", "INVALID_EXTENSION", "Le nom du fichier doit se terminer par .csv."));
        }
        if (file.getSize() > properties.maxFileSize().toBytes()) {
            errors.add(error(null, "file", "FILE_TOO_LARGE",
                    "Le fichier dépasse la taille maximale de " + properties.maxFileSize().toMegabytes() + " Mo."));
        }
        String contentType = file.getContentType();
        if (contentType != null && !contentType.isBlank()) {
            String normalizedContentType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!ALLOWED_CONTENT_TYPES.contains(normalizedContentType)) {
                errors.add(error(null, "file", "INVALID_CONTENT_TYPE",
                        "Le type de fichier reçu n'est pas compatible avec un CSV."));
            }
        }
        return errors;
    }

    private List<CsvImportError> validateHeaders(List<String> actualHeaders, Set<String> expectedHeaders) {
        List<CsvImportError> errors = new ArrayList<>();
        Set<String> actual = new LinkedHashSet<>();
        for (String header : actualHeaders) {
            if (!actual.add(header)) {
                errors.add(error(1, header, "DUPLICATE_HEADER", "Cette colonne est déclarée plusieurs fois."));
            }
        }
        for (String expected : expectedHeaders) {
            if (!actual.contains(expected)) {
                errors.add(error(1, expected, "MISSING_HEADER", "Cette colonne obligatoire manque dans l'en-tête."));
            }
        }
        for (String header : actual) {
            if (!expectedHeaders.contains(header)) {
                errors.add(error(1, header, "UNKNOWN_HEADER", "Cette colonne n'est pas reconnue."));
            }
        }
        return errors;
    }

    private String requiredText(
            CSVRecord record,
            int line,
            String field,
            int maxLength,
            List<CsvImportError> errors) {
        String text = value(record, field);
        if (text.isBlank()) {
            errors.add(error(line, field, "REQUIRED_VALUE", "Cette valeur est obligatoire."));
            return null;
        }
        if (text.length() > maxLength) {
            errors.add(error(line, field, "VALUE_TOO_LONG",
                    "Cette valeur ne peut pas dépasser " + maxLength + " caractères."));
        }
        return text;
    }

    private String optionalText(
            CSVRecord record,
            int line,
            String field,
            int maxLength,
            List<CsvImportError> errors) {
        String text = value(record, field);
        if (text.isBlank()) {
            return null;
        }
        if (text.length() > maxLength) {
            errors.add(error(line, field, "VALUE_TOO_LONG",
                    "Cette valeur ne peut pas dépasser " + maxLength + " caractères."));
        }
        return text;
    }

    private BigDecimal decimal(
            CSVRecord record,
            int line,
            String field,
            boolean optional,
            List<CsvImportError> errors) {
        String text = value(record, field);
        if (text.isBlank()) {
            if (!optional) {
                errors.add(error(line, field, "REQUIRED_VALUE", "Cette valeur est obligatoire."));
            }
            return null;
        }
        try {
            return new BigDecimal(normalizeDecimal(text));
        } catch (NumberFormatException exception) {
            errors.add(error(line, field, "INVALID_NUMBER", "Utilisez un nombre décimal valide."));
            return null;
        }
    }

    private Double coordinate(
            CSVRecord record,
            int line,
            String field,
            double minimum,
            double maximum,
            List<CsvImportError> errors) {
        String text = value(record, field);
        if (text.isBlank()) {
            return null;
        }
        try {
            double number = new BigDecimal(normalizeDecimal(text)).doubleValue();
            if (number < minimum || number > maximum) {
                errors.add(error(line, field, "OUT_OF_RANGE",
                        "La valeur doit être comprise entre " + minimum + " et " + maximum + "."));
            }
            return number;
        } catch (NumberFormatException exception) {
            errors.add(error(line, field, "INVALID_NUMBER", "Utilisez un nombre décimal valide."));
            return null;
        }
    }

    private OffsetDateTime offsetDateTime(
            CSVRecord record,
            int line,
            String field,
            List<CsvImportError> errors) {
        String text = value(record, field);
        if (text.isBlank()) {
            return null;
        }
        try {
            OffsetDateTime value = OffsetDateTime.parse(text);
            if (!value.getOffset().equals(java.time.ZoneOffset.ofHours(3))) {
                errors.add(error(line, field, "TIMEZONE_OFFSET_MISMATCH",
                        "Utilisez une date ISO avec le décalage +03:00 d'Antananarivo."));
            }
            return value;
        } catch (DateTimeParseException exception) {
            errors.add(error(line, field, "INVALID_DATE_TIME",
                    "Utilisez une date ISO, par exemple 2026-08-05T09:00:00+03:00."));
            return null;
        }
    }

    private VehicleType vehicleType(CSVRecord record, int line, List<CsvImportError> errors) {
        String text = value(record, "vehicle_type");
        if (text.isBlank()) {
            errors.add(error(line, "vehicle_type", "REQUIRED_VALUE", "Cette valeur est obligatoire."));
            return null;
        }
        try {
            return VehicleType.valueOf(text.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            errors.add(error(line, "vehicle_type", "UNKNOWN_VEHICLE_TYPE",
                    "Les types acceptés sont MOTO et BICYCLE."));
            return null;
        }
    }

    private void requirePositive(
            BigDecimal number,
            int line,
            String field,
            List<CsvImportError> errors) {
        if (number != null && number.signum() <= 0) {
            errors.add(error(line, field, "NOT_POSITIVE", "La valeur doit être strictement positive."));
        }
    }

    private String value(CSVRecord record, String field) {
        return record.get(field).trim();
    }

    private String normalizeDecimal(String value) {
        String normalized = value
                .replace("\u00A0", "")
                .replace(" ", "");
        if (normalized.indexOf(',') >= 0 && normalized.indexOf('.') >= 0) {
            throw new NumberFormatException("Mixed decimal separators");
        }
        return normalized.replace(',', '.');
    }

    private String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
    }

    private Character detectDelimiter(String content) {
        String firstLine = content.lines()
                .filter(line -> !line.isBlank())
                .findFirst()
                .orElse("");
        int semicolons = countOutsideQuotes(firstLine, ';');
        int commas = countOutsideQuotes(firstLine, ',');
        if (semicolons == 0 && commas == 0) {
            return null;
        }
        return semicolons >= commas ? ';' : ',';
    }

    private int countOutsideQuotes(String line, char candidate) {
        boolean quoted = false;
        int count = 0;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (character == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') {
                    index++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted && character == candidate) {
                count++;
            }
        }
        return count;
    }

    private String safeFilename(String originalFilename) {
        if (originalFilename == null || originalFilename.isBlank()) {
            return "import.csv";
        }
        return originalFilename.replace('\\', '/').substring(originalFilename.replace('\\', '/').lastIndexOf('/') + 1);
    }

    private int logicalLine(CSVRecord record) {
        return Math.toIntExact(record.getRecordNumber() + 1);
    }

    private void throwIfErrors(ParsedCsv csv, List<CsvImportError> errors) {
        if (!errors.isEmpty()) {
            throw new CsvImportException(csv.importType(), csv.filename(), csv.delimiter(), errors);
        }
    }

    private CsvImportError error(Integer line, String field, String code, String message) {
        return new CsvImportError(line, field, code, message);
    }

    private record ParsedCsv(
            String filename,
            char delimiter,
            List<CSVRecord> records,
            CsvImportType importType) {
    }
}
