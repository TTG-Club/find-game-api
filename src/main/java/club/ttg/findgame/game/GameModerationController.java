package club.ttg.findgame.game;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Действия модератора над объявлениями игры. */
@RestController
@RequestMapping("/api/v1/moderation/games")
@SecurityRequirement(name = "bearerAuth")
public class GameModerationController {

    private static final String ALL_MASTER_GAMES_DELETION_REASON =
            "Скрыто модератором по жалобе на игру";

    private final GameService service;

    public GameModerationController(GameService service) {
        this.service = service;
    }

    /** Скрывает все активные объявления мастера указанной игры. */
    @DeleteMapping("/{gameId}/master-games")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Скрыть все игры мастера по жалобе")
    public void deleteAllMasterGames(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId
    ) {
        service.deleteAllByReportedGame(
                UUID.fromString(jwt.getSubject()), gameId, ALL_MASTER_GAMES_DELETION_REASON);
    }
}
