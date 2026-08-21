package club.ttg.findgame.subscription;

import club.ttg.findgame.config.InternalServiceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SubscriptionStatusClientTest {

    private static final String BASE_URL = "http://subscriber.test";
    private static final String SERVICE_SECRET = "shared-secret";

    private MockRestServiceServer server;
    private SubscriptionStatusClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        InternalServiceProperties properties = new InternalServiceProperties();
        properties.setServiceSecret(SERVICE_SECRET);
        client = new SubscriptionStatusClient(properties, builder.baseUrl(BASE_URL).build());
    }

    @Test
    void readsRealTimeSubscriptionStatusByUsername() {
        server.expect(requestTo(BASE_URL + "/api/internal/subscriptions/game-master/status"))
                .andExpect(method(GET))
                .andExpect(header("X-Service-Token", SERVICE_SECRET))
                .andRespond(withSuccess("""
                        {"active":true,"registered":true,"type":"BUY"}
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.status("game-master"))
                .hasValueSatisfying(status -> assertThat(status.active()).isTrue());
        server.verify();
    }

    @Test
    void returnsUnknownStatusWhenSubscriberServiceFails() {
        server.expect(requestTo(BASE_URL + "/api/internal/subscriptions/game-master/status"))
                .andExpect(method(GET))
                .andRespond(withServerError());

        assertThat(client.status("game-master")).isEmpty();
        server.verify();
    }
}
