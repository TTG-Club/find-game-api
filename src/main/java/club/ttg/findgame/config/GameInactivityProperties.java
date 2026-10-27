package club.ttg.findgame.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** Сроки, по которым брошенная мастером игра отменяется сама. */
@Getter
@Setter
@ConfigurationProperties(prefix = "game-inactivity")
public class GameInactivityProperties {

    /** Выключенный планировщик не задаёт вопросов и ничего не отменяет. */
    private boolean enabled = true;

    /** Сколько мастер может ничего не делать в игре, пока его не спросят. */
    private Duration idlePeriod = Duration.ofDays(30);

    /** Сколько ждать ответа на вопрос, прежде чем отменить игру. */
    private Duration gracePeriod = Duration.ofDays(3);

    /** Как часто планировщик проверяет игры. */
    private Duration pollDelay = Duration.ofMinutes(15);

    /** Сколько игр разбирается за один проход. */
    private int batchSize = 50;
}
