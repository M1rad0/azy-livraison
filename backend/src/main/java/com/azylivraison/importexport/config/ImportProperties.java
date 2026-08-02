package com.azylivraison.importexport.config;

import jakarta.validation.constraints.NotNull;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("app.imports")
public record ImportProperties(@NotNull DataSize maxFileSize) {
}
