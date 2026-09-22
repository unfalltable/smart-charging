package io.smartcharge.platform.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("charging.identity.wechat")
public record WeChatIdentityProperties(String appId, String appSecret, String tenantCode) { }
