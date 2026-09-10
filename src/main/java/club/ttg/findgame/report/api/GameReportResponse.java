package club.ttg.findgame.report.api;

import club.ttg.findgame.report.GameReportReason;

import java.time.Instant;
import java.util.UUID;

/** Жалоба в очереди модератора вместе с названием объявления. */
public record GameReportResponse(
        UUID id,
        UUID gameId,
        String gameTitle,
        UUID reporterId,
        GameReportReason reason,
        String details,
        Instant createdAt
) {
}
