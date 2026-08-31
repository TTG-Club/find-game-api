package club.ttg.findgame.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "rate-limit")
public class RateLimitProperties {

    private boolean enabled = true;

    @Min(1)
    private int requests = 75;

    @NotNull
    private Duration interval = Duration.ofMinutes(1);

    @Min(1)
    @Max(1_000_000)
    private int maxClients = 100_000;

    @AssertTrue(message = "rate-limit.interval must be between 1 second and 24 hours")
    public boolean isIntervalValid() {
        return interval != null && interval.compareTo(Duration.ofSeconds(1)) >= 0
                && interval.compareTo(Duration.ofHours(24)) <= 0;
    }
}
