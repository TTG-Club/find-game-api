package club.ttg.findgame.game.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Публичные сведения о ближайшей встрече, без состава и платёжных реквизитов. */
public record NextGameSessionResponse(
        UUID id,
        Instant startsAt,
        Integer estimatedDurationMinutes,
        BigDecimal priceAmount,
        String priceCurrency
) {}
