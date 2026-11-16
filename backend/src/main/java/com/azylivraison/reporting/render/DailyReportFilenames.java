package com.azylivraison.reporting.render;

import java.text.Normalizer;
import java.time.LocalDate;

/** Concise, filesystem-safe report names; technical database IDs are never exported. */
public final class DailyReportFilenames {

    private DailyReportFilenames() {
    }

    public static String stem(String readableIdentifier, LocalDate serviceDate) {
        String identifier = Normalizer.normalize(readableIdentifier == null ? "" : readableIdentifier,
                        Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^[._-]+|[._-]+$", "");
        if (identifier.isBlank()) identifier = "rapport";
        if (identifier.length() > 72) identifier = identifier.substring(0, 72).replaceAll("[._-]+$", "");
        return identifier + "-" + serviceDate;
    }
}
