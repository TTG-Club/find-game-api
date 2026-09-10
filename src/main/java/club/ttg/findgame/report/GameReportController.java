package club.ttg.findgame.report;

import club.ttg.findgame.report.api.CreateGameReportRequest;
import club.ttg.findgame.report.api.GameReportResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Жалобы пользователей на игры и очередь проверки модератора. */
@Validated
@RestController
@SecurityRequirement(name = "bearerAuth")
public class GameReportController {

    private final GameReportService service;

    public GameReportController(GameReportService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/games/{gameId}/reports")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Пожаловаться на игру")
    public void create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @Valid @RequestBody CreateGameReportRequest request
    ) {
        service.create(UUID.fromString(jwt.getSubject()), gameId, request);
    }

    @GetMapping("/api/v1/moderation/game-reports")
    @Operation(summary = "Получить жалобы на игры для модерации")
    public Page<GameReportResponse> findAll(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.findAll(page, size);
    }
}
