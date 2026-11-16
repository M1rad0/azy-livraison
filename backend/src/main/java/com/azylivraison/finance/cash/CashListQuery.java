package com.azylivraison.finance.cash;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;

import com.azylivraison.finance.service.FinanceInputException;

/** One bounded, zero-based paging contract for finance lists. Date boundaries are local to Madagascar. */
public record CashListQuery(LocalDate from, LocalDate to, String q, int page, int size) {
    private static final ZoneId ZONE = ZoneId.of("Indian/Antananarivo");

    public CashListQuery {
        if (page < 0) throw new FinanceInputException("Le numéro de page doit être positif ou nul.");
        if (size < 1 || size > 100) throw new FinanceInputException("Une page doit contenir entre 1 et 100 éléments.");
        if (from != null && to != null && from.isAfter(to))
            throw new FinanceInputException("La date de début doit précéder ou être égale à la date de fin.");
        q = q == null ? "" : q.trim();
        if (q.length() > 200) throw new FinanceInputException("La recherche ne doit pas dépasser 200 caractères.");
        // Bound dates before JDBC conversion; extreme dates must produce a user error, not a 500.
        try {
            if (from != null) Timestamp.from(from.atStartOfDay(ZONE).toInstant());
            if (to != null) Timestamp.from(to.plusDays(1).atStartOfDay(ZONE).toInstant());
        } catch (DateTimeException | IllegalArgumentException exception) {
            throw new FinanceInputException("La période demandée n'est pas valide.");
        }
    }

    public long offset() { return (long) page * size; }
    public boolean hasSearch() { return !q.isEmpty(); }
    public Timestamp fromTimestamp() { return Timestamp.from(from.atStartOfDay(ZONE).toInstant()); }
    public Timestamp untilTimestamp() { return Timestamp.from(to.plusDays(1).atStartOfDay(ZONE).toInstant()); }

    /** Treat percent/underscore as literal text rather than letting searches bypass their own filter. */
    public String searchPattern() {
        return pattern(q);
    }

    /** Search compact local numbers while preserving immutable, older snapshots stored with spaces/+261. */
    public String phoneSearchPattern() {
        String compact = q.replaceAll("[\\s().-]", "");
        if (compact.isEmpty()) return searchPattern();
        if (compact.startsWith("+261")) compact = "0" + compact.substring(4);
        else if (compact.startsWith("00261")) compact = "0" + compact.substring(5);
        return pattern(compact);
    }

    public static String phoneSearchExpression(String column) {
        // Only hard-coded column names are passed by repositories, never request data.
        String compact = "REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(COALESCE(" + column
                + ", ''), ' ', ''), '-', ''), '.', ''), '(', ''), ')', '')";
        return "LOWER(CASE WHEN " + compact + " LIKE '+261%' THEN '0' || SUBSTRING(" + compact
                + ", 5) WHEN " + compact + " LIKE '00261%' THEN '0' || SUBSTRING(" + compact
                + ", 6) ELSE " + compact + " END)";
    }

    private static String pattern(String value) {
        return "%" + value.toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
    }
}
