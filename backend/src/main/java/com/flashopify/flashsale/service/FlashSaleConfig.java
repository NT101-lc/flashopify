package com.flashopify.flashsale.service;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Registers {@link FlashSaleProperties} and enables the campaign lifecycle scheduler. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties(FlashSaleProperties.class)
public class FlashSaleConfig {}
