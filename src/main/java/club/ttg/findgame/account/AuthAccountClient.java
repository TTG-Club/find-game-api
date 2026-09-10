package club.ttg.findgame.account;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Состояние учётной записи спрашивается у auth-service тем же токеном, с
 * которым пришёл пользователь: в самом токене признака подтверждённой почты
 * нет, а держать его копию у себя значило бы отвечать по устаревшим данным.
 */
@Slf4j
@Component
public class AuthAccountClient {

    private final RestClient restClient;

    public AuthAccountClient(RestClient authServiceRestClient) {
        this.restClient = authServiceRestClient;
    }

    /**
     * Подтверждена ли почта у владельца токена.
     *
     * @param accessToken Токен запроса — им же авторизуется обращение к auth-service.
     * @throws AccountStatusUnavailableException auth-service не ответил: без
     * ответа состояние учётной записи неизвестно, и решать за него нельзя.
     */
    public boolean isEmailVerified(String accessToken) {
        if (!StringUtils.hasText(accessToken)) {
            throw new AccountStatusUnavailableException();
        }

        try {
            AuthAccount account = restClient.get()
                    .uri("/api/auth/me")
                    .headers(headers -> headers.set(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                    .retrieve()
                    .body(AuthAccount.class);

            if (account == null) {
                throw new AccountStatusUnavailableException();
            }

            return account.emailVerified();
        } catch (RestClientException exception) {
            log.warn("Не удалось получить состояние учётной записи из auth-service", exception);
            throw new AccountStatusUnavailableException();
        }
    }

    /** Учётная запись глазами auth-service; остальные её поля здесь не нужны. */
    record AuthAccount(boolean emailVerified) {
    }
}
