package club.ttg.findgame.config;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecurityConfigurationJwtDecoderTest {

    private static final String SECRET_32_BYTES = "0123456789abcdef0123456789abcdef";
    private static final String SECRET_64_BYTES = SECRET_32_BYTES + SECRET_32_BYTES;

    private final SecurityConfiguration configuration = new SecurityConfiguration();

    @Test
    void decodesAuthServiceHs256Token() {
        UUID userId = UUID.randomUUID();
        Jwt jwt = configuration.jwtDecoder(SECRET_32_BYTES)
                .decode(issueToken(SECRET_32_BYTES, userId, Instant.now().plus(1, ChronoUnit.HOURS)));

        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS256");
        assertThat(jwt.getSubject()).isEqualTo(userId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
    }

    @Test
    void decodesAuthServiceHs512Token() {
        Jwt jwt = configuration.jwtDecoder(SECRET_64_BYTES)
                .decode(issueToken(SECRET_64_BYTES, UUID.randomUUID(), Instant.now().plus(1, ChronoUnit.HOURS)));

        assertThat(jwt.getHeaders().get("alg")).isEqualTo("HS512");
    }

    @Test
    void rejectsExpiredToken() {
        JwtDecoder decoder = configuration.jwtDecoder(SECRET_32_BYTES);
        String token = issueToken(
                SECRET_32_BYTES,
                UUID.randomUUID(),
                Instant.now().minus(1, ChronoUnit.HOURS)
        );

        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenSignedWithAnotherSecret() {
        String token = issueToken(
                "ffffffffffffffffffffffffffffffff",
                UUID.randomUUID(),
                Instant.now().plus(1, ChronoUnit.HOURS)
        );

        assertThatThrownBy(() -> configuration.jwtDecoder(SECRET_32_BYTES).decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsMalformedToken() {
        assertThatThrownBy(() -> configuration.jwtDecoder(SECRET_32_BYTES).decode("garbage"))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenWithNonUuidSubject() {
        SecretKey key = Keys.hmacShaKeyFor(SECRET_32_BYTES.getBytes(StandardCharsets.UTF_8));
        String token = Jwts.builder()
                .subject("not-a-uuid")
                .claim("roles", List.of("USER"))
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();

        assertThatThrownBy(() -> configuration.jwtDecoder(SECRET_32_BYTES).decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void failsFastOnShortSecret() {
        assertThatThrownBy(() -> configuration.jwtDecoder("too-short"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("too short");
    }

    private String issueToken(String secret, UUID userId, Instant expiresAt) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .subject(userId.toString())
                .claim("username", "game-master")
                .claim("roles", List.of("USER"))
                .issuedAt(Date.from(Instant.now().minus(1, ChronoUnit.MINUTES)))
                .expiration(Date.from(expiresAt))
                .signWith(key)
                .compact();
    }
}
