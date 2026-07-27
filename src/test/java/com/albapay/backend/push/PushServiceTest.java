package com.albapay.backend.push;

import com.albapay.backend.config.AlbapayProperties;
import com.albapay.backend.toss.TossClient;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class PushServiceTest {
    @Test
    void 이벤트에맞는템플릿으로발송한다() {
        TossClient tossClient = mock(TossClient.class);
        AlbapayProperties properties = properties();
        PushService service = new PushService(tossClient, properties);
        Map<String, Object> context = Map.of("이름", "홍길동");

        service.send(Map.of(
                "event", "worker_joined",
                "recipientUserKey", 7,
                "context", context));

        verify(tossClient).sendMessage(7L, "worker-join", context);
    }

    @Test
    void 수신자가없으면발송하지않는다() {
        TossClient tossClient = mock(TossClient.class);
        PushService service = new PushService(tossClient, properties());

        service.send(Map.of("event", "payout_completed", "context", Map.of()));

        verifyNoInteractions(tossClient);
    }

    @Test
    void 토스오류가본요청으로전파되지않는다() {
        TossClient tossClient = mock(TossClient.class);
        PushService service = new PushService(tossClient, properties());
        doThrow(new IllegalStateException("failed"))
                .when(tossClient).sendMessage(7L, "worklog-reviewed", Map.of());

        service.send(Map.of(
                "event", "worklog_reviewed",
                "recipientUserKey", 7,
                "context", Map.of()));

        verify(tossClient).sendMessage(7L, "worklog-reviewed", Map.of());
    }

    private static AlbapayProperties properties() {
        AlbapayProperties properties = new AlbapayProperties();
        properties.getToss().setPushWorkerJoinTemplate("worker-join");
        properties.getToss().setPushWorklogSubmittedTemplate("worklog-submitted");
        properties.getToss().setPushWorklogReviewedTemplate("worklog-reviewed");
        properties.getToss().setPushPayoutCompletedTemplate("payout-completed");
        return properties;
    }
}
