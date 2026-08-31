package club.ttg.findgame.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(PropertiesConfiguration.class);

    @Test
    void bindsConfigurableQuotaAndInterval() {
        contextRunner.withPropertyValues("rate-limit.requests=120", "rate-limit.interval=2m",
                        "rate-limit.max-clients=2000", "rate-limit.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RateLimitProperties properties = context.getBean(RateLimitProperties.class);
                    assertThat(properties.getRequests()).isEqualTo(120);
                    assertThat(properties.getInterval()).isEqualTo(Duration.ofMinutes(2));
                    assertThat(properties.getMaxClients()).isEqualTo(2000);
                    assertThat(properties.isEnabled()).isFalse();
                });
    }

    @Test
    void rejectsUnsafeConfigurationAtStartup() {
        for (String property : new String[]{"rate-limit.requests=0", "rate-limit.interval=0s",
                "rate-limit.interval=25h", "rate-limit.max-clients=0", "rate-limit.max-clients=1000001"}) {
            contextRunner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(RateLimitProperties.class)
    static class PropertiesConfiguration {
    }
}
