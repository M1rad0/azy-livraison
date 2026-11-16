package com.azylivraison.reporting.render;

/** A rendered attachment, including the actual format selected for image exports. */
public record ReportDownload(byte[] content, String filename, String mediaType) {
}
