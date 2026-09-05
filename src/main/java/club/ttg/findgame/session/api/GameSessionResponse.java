package club.ttg.findgame.session.api;

import club.ttg.findgame.session.GameSessionStatus;
import club.ttg.findgame.session.SessionPaymentType;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public record GameSessionResponse(
        UUID id,
        UUID gameId,
        String title,
        Instant startsAt,
        @JsonInclude(JsonInclude.Include.NON_NULL) Integer estimatedDurationMinutes,
        GameSessionStatus status,
        @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal priceAmount,
        @JsonInclude(JsonInclude.Include.NON_NULL) String priceCurrency,
        @JsonInclude(JsonInclude.Include.NON_NULL) SessionPaymentType paymentType,
        @JsonInclude(JsonInclude.Include.NON_NULL) Instant completedAt,
        Set<UUID> registeredPlayerIds
) {
}
