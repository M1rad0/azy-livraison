package com.azylivraison.importexport.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ImportProperties.class)
public class ImportConfiguration {
}
