package io.smartcharge.platform.shared.web;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("charging.rate-limit")
public record RateLimitProperties(int defaultPerMinute, int publicPerMinute, int loginPerMinute) { }
