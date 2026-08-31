package club.ttg.findgame.config;

import club.ttg.findgame.security.RateLimitFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties({InternalServiceProperties.class, RateLimitProperties.class})
public class SecurityConfiguration {

    private static final String[] PUBLIC_PATHS = {
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs",
            "/v3/api-docs/**",
            "/v3/api-docs.yaml",
            "/actuator/health"
    };

    private static final String PUBLIC_GAME_BY_ID =
            "/api/v1/games/{id:[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}}";

    private static final String[] INTERNAL_PATHS = {"/api/v1/internal", "/api/v1/internal/**"};

    private static final int MIN_SECRET_LENGTH_BYTES = 32;

    @Bean
    RateLimitFilter rateLimitFilter(RateLimitProperties properties) {
        return new RateLimitFilter(properties);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(INTERNAL_PATHS).permitAll()
                        .requestMatchers(HttpMethod.DELETE, PUBLIC_GAME_BY_ID)
                        .hasAnyRole("ADMIN", "MODERATOR")
                        .requestMatchers(HttpMethod.GET, "/api/v1/games", PUBLIC_GAME_BY_ID).permitAll()
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt ->
                        jwt.jwtAuthenticationConverter(jwtAuthenticationConverter())));

        return http.build();
    }

    @Bean
    JwtDecoder jwtDecoder(@Value("${auth-service.jwt-secret}") String secret) {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_LENGTH_BYTES) {
            throw new IllegalStateException(
                    "auth-service.jwt-secret is too short. Current length: " + keyBytes.length
                            + " bytes. Minimum required length for HS256 is " + MIN_SECRET_LENGTH_BYTES + " bytes."
            );
        }

        MacAlgorithm algorithm = resolveMacAlgorithm(keyBytes.length);
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(new SecretKeySpec(keyBytes, jcaAlgorithmName(algorithm)))
                .macAlgorithm(algorithm)
                .build();
        OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(),
                SecurityConfiguration::validateSubject
        );
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(SecurityConfiguration::extractRoles);
        return converter;
    }

    private static Collection<GrantedAuthority> extractRoles(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        if (roles == null) {
            return List.of();
        }
        return roles.stream()
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
    }

    private static OAuth2TokenValidatorResult validateSubject(Jwt jwt) {
        try {
            UUID.fromString(jwt.getSubject());
            return OAuth2TokenValidatorResult.success();
        } catch (IllegalArgumentException | NullPointerException exception) {
            OAuth2Error error = new OAuth2Error(
                    OAuth2ErrorCodes.INVALID_TOKEN,
                    "JWT subject must be a UUID",
                    null
            );
            return OAuth2TokenValidatorResult.failure(error);
        }
    }

    private static MacAlgorithm resolveMacAlgorithm(int secretLengthBytes) {
        int secretLengthBits = secretLengthBytes * 8;
        if (secretLengthBits >= 512) {
            return MacAlgorithm.HS512;
        }
        if (secretLengthBits >= 384) {
            return MacAlgorithm.HS384;
        }
        return MacAlgorithm.HS256;
    }

    private static String jcaAlgorithmName(MacAlgorithm algorithm) {
        if (MacAlgorithm.HS512.equals(algorithm)) {
            return "HmacSHA512";
        }
        if (MacAlgorithm.HS384.equals(algorithm)) {
            return "HmacSHA384";
        }
        return "HmacSHA256";
    }
}
