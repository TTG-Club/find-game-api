package club.ttg.findgame.session;

import club.ttg.findgame.session.api.CreateGameSessionRequest;
import club.ttg.findgame.session.api.CopyGameSessionRequest;
import club.ttg.findgame.session.api.GameSessionResponse;
import club.ttg.findgame.session.api.ScheduleGameSessionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/games/{gameId}/sessions")
@Tag(name = "Game sessions")
@SecurityRequirement(name = "bearerAuth")
public class GameSessionController {

    private final GameSessionService service;

    public GameSessionController(GameSessionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать сессию игры")
    public GameSessionResponse create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @Valid @RequestBody CreateGameSessionRequest request
    ) {
        return service.create(UUID.fromString(jwt.getSubject()), gameId, request);
    }

    @PostMapping("/{sourceSessionId}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать новую сессию копированием предыдущей")
    public GameSessionResponse copy(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sourceSessionId,
            @Valid @RequestBody CopyGameSessionRequest request
    ) {
        return service.copy(
                UUID.fromString(jwt.getSubject()), gameId, sourceSessionId, request);
    }

    @PatchMapping("/{sessionId}/schedule")
    @Operation(summary = "Назначить дату сессии, объявленной с открытой датой")
    public GameSessionResponse schedule(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody ScheduleGameSessionRequest request
    ) {
        return service.schedule(
                UUID.fromString(jwt.getSubject()), gameId, sessionId, request);
    }

    @GetMapping
    @Operation(summary = "Получить сессии игры")
    public List<GameSessionResponse> findByGame(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @RequestParam(required = false) UUID inviteCode
    ) {
        return service.findByGame(UUID.fromString(jwt.getSubject()), gameId, inviteCode);
    }
}
