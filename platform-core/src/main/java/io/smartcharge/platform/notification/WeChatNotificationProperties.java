package io.smartcharge.platform.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("charging.notification.wechat")
record WeChatNotificationProperties(boolean enabled, String templatesJson) { }
