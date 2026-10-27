package club.ttg.findgame.account;

import club.ttg.findgame.config.AuthServiceProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.util.UUID;

/**
 * Письма через auth-service: почта пользователей и SMTP живут там, и заводить
 * их второй раз ради пары писем незачем.
 *
 * Письмо — дополнение к уведомлению на сайте, а не замена ему: если
 * auth-service не ответил, вопрос мастеру всё равно уже лежит в ленте.
 */
@Slf4j
@Component
public class AuthMailClient {

    private static final String SERVICE_TOKEN_HEADER = "X-Service-Token";

    private final RestClient restClient;
    private final String serviceToken;

    public AuthMailClient(RestClient authServiceRestClient, AuthServiceProperties properties) {
        this.restClient = authServiceRestClient;
        this.serviceToken = properties.getServiceToken();
    }

    /**
     * Спрашивает мастера письмом, актуальна ли ещё его игра.
     *
     * @param userId Мастер: адрес auth-service находит сам.
     * @param gameTitle Название игры.
     * @param gameUrl Страница игры, где можно ответить.
     * @param cancelAt Когда игра отменится без ответа.
     */
    public void sendGameInactive(UUID userId, String gameTitle, String gameUrl, Instant cancelAt) {
        if (!StringUtils.hasText(serviceToken)) {
            log.warn("Секрет auth-service не задан: письмо о неактивной игре не отправлено");

            return;
        }

        try {
            restClient.post()
                    .uri("/api/internal/mail/game-inactive")
                    .headers(headers -> headers.set(SERVICE_TOKEN_HEADER, serviceToken))
                    .body(new GameInactiveMail(userId, gameTitle, gameUrl, cancelAt))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientException exception) {
            log.warn("Не удалось отправить письмо о неактивной игре мастеру {}", userId, exception);
        }
    }

    /** Тело запроса к auth-service. */
    record GameInactiveMail(UUID userId, String gameTitle, String gameUrl, Instant cancelAt) {
    }
}
