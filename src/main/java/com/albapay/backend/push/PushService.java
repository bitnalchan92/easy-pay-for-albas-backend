package com.albapay.backend.push;

import com.albapay.backend.config.AlbapayProperties;
import com.albapay.backend.toss.TossClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class PushService {
    private final TossClient tossClient;
    private final AlbapayProperties.Toss config;

    public PushService(@Lazy TossClient tossClient, AlbapayProperties properties) {
        this.tossClient = tossClient;
        this.config = properties.getToss();
    }

    public void send(Object value) {
        if (!(value instanceof Map<?, ?> notification)) return;

        String event = string(notification.get("event"));
        String template = template(event);
        Long recipientUserKey = number(notification.get("recipientUserKey"));
        if (template == null || template.isBlank() || recipientUserKey == null) return;
        if (!(notification.get("context") instanceof Map<?, ?> rawContext)) return;

        Map<String, Object> context = new LinkedHashMap<>();
        rawContext.forEach((key, item) -> context.put(String.valueOf(key), item));
        try {
            tossClient.sendMessage(recipientUserKey, template, context);
        } catch (RuntimeException e) {
            log.warn("Toss push failed: event={}, error={}", event, e.getClass().getSimpleName());
        }
    }

    private String template(String event) {
        if (event == null) return null;
        return switch (event) {
            case "worker_joined" -> config.getPushWorkerJoinTemplate();
            case "worklog_submitted" -> config.getPushWorklogSubmittedTemplate();
            case "worklog_reviewed" -> config.getPushWorklogReviewedTemplate();
            case "payout_completed" -> config.getPushPayoutCompletedTemplate();
            default -> null;
        };
    }

    private static String string(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Long number(Object value) {
        if (value == null) return null;
        try {
            return Long.valueOf(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
