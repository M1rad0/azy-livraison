package com.azylivraison.optimization.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(OptimizationProperties.class)
public class OptimizationConfiguration {
}
