package club.ttg.findgame.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Getter
@Setter
@ConfigurationProperties(prefix = "internal")
public class InternalServiceProperties {

    private String serviceSecret;
}
