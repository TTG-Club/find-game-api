package club.ttg.findgame.report.api;

import club.ttg.findgame.report.GameReportReason;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Данные жалобы на игру. */
public record CreateGameReportRequest(
        @NotNull GameReportReason reason,
        @Size(max = 1000) String details
) {
}
