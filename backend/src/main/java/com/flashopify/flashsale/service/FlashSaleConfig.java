package com.flashopify.flashsale.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers {@link FlashSaleProperties}. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FlashSaleProperties.class)
public class FlashSaleConfig {}
