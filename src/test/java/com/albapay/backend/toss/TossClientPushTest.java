package com.albapay.backend.toss;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.Map;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TossClientPushTest {
    @Test
    void 기능성메시지스펙으로호출한다() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://apps-in-toss-api.toss.im");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        TossClient client = new TossClient(builder.build());
        server.expect(requestTo(
                        "https://apps-in-toss-api.toss.im/api-partner/v1/apps-in-toss/messenger/send-message"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("x-toss-user-key", "7"))
                .andExpect(content().json("""
                        {
                          "templateSetCode": "template",
                          "context": {"workerName": "홍길동"}
                        }
                        """))
                .andRespond(withSuccess(
                        "{\"resultType\":\"SUCCESS\",\"success\":{\"msgCount\":1}}",
                        MediaType.APPLICATION_JSON));

        client.sendMessage(7L, "template", Map.of("workerName", "홍길동"));

        server.verify();
    }
}
