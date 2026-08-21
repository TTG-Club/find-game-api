package club.ttg.findgame.subscription;

import club.ttg.findgame.config.InternalServiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.Optional;

@Slf4j
@Component
public class SubscriptionStatusClient {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final InternalServiceProperties internalProperties;
    private final RestClient restClient;

    public SubscriptionStatusClient(
            InternalServiceProperties internalProperties,
            RestClient subscriberServiceRestClient
    ) {
        this.internalProperties = internalProperties;
        this.restClient = subscriberServiceRestClient;
    }

    public Optional<SubscriptionStatus> status(String username) {
        if (!StringUtils.hasText(username)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(restClient.get()
                    .uri("/api/internal/subscriptions/{username}/status", username)
                    .headers(this::addServiceHeaders)
                    .retrieve()
                    .body(SubscriptionStatus.class));
        } catch (RestClientException exception) {
            log.warn("Не удалось получить статус подписки {} из subscriber-service", username, exception);
            return Optional.empty();
        }
    }

    private void addServiceHeaders(HttpHeaders headers) {
        if (StringUtils.hasText(internalProperties.getServiceSecret())) {
            headers.set(SERVICE_TOKEN_HEADER, internalProperties.getServiceSecret());
        }
    }

    public record SubscriptionStatus(
            boolean active,
            boolean registered,
            Instant expiresAt,
            Instant startsAt,
            String type
    ) {
    }
}
