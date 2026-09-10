package club.ttg.findgame.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Адрес auth-service: у него спрашивают состояние учётной записи автора запроса. */
@Getter
@Setter
@ConfigurationProperties(prefix = "auth-service")
public class AuthServiceProperties {

    private String baseUrl;
    private Duration connectTimeout = Duration.ofSeconds(2);
    private Duration readTimeout = Duration.ofSeconds(3);
}
