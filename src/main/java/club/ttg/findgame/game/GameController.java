package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.GameResponse;
import club.ttg.findgame.game.api.DeleteGameRequest;
import club.ttg.findgame.game.api.GameSearchFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/v1/games")
@Tag(name = "Games")
public class GameController {

    private final GameService service;

    public GameController(GameService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать игру")
    @SecurityRequirement(name = "bearerAuth")
    public GameResponse create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateGameRequest request
    ) {
        return service.create(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("username"), request);
    }

    @GetMapping
    @Operation(summary = "Найти публичные игры")
    public Page<GameResponse> findPublic(
            @RequestParam(required = false) Set<GameSystem> system,
            @RequestParam(required = false) Set<GameSystem> excludeSystem,
            @RequestParam(required = false) Set<GameType> type,
            @RequestParam(required = false) Set<GameType> excludeType,
            @RequestParam(required = false) Set<GameDurationType> durationType,
            @RequestParam(required = false) Set<GameDurationType> excludeDurationType,
            @RequestParam(required = false) Set<GameCostType> costType,
            @RequestParam(required = false) Set<GameCostType> excludeCostType,
            @RequestParam(required = false) Set<GameStatus> status,
            @RequestParam(required = false) Set<GameStatus> excludeStatus,
            @RequestParam(required = false) Set<String> city,
            @RequestParam(required = false) Set<String> excludeCity,
            @RequestParam(required = false) Boolean crossplayAllowed,
            @RequestParam(required = false) @Min(0) @Max(150) Integer minAge,
            @RequestParam(required = false) @Min(0) @Max(150) Integer maxAge,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        GameSearchFilter filter = new GameSearchFilter(
                system, excludeSystem, type, excludeType,
                durationType, excludeDurationType, costType, excludeCostType,
                status, excludeStatus, city, excludeCity, crossplayAllowed, minAge, maxAge);
        return service.findPublic(filter, page, size);
    }

    @GetMapping("/{gameId}")
    @Operation(summary = "Получить публичную игру или приватную игру по коду приглашения")
    public GameResponse get(
            @PathVariable UUID gameId,
            @RequestParam(required = false) UUID inviteCode
    ) {
        return service.get(gameId, inviteCode);
    }

    @PatchMapping("/{gameId}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Завершить свою игру")
    @SecurityRequirement(name = "bearerAuth")
    public void close(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId
    ) {
        service.close(UUID.fromString(jwt.getSubject()), gameId);
    }

    @DeleteMapping("/{gameId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Скрыть игру мягким удалением (ADMIN или MODERATOR)")
    @SecurityRequirement(name = "bearerAuth")
    public void delete(
            @PathVariable UUID gameId,
            @Valid @RequestBody(required = false) DeleteGameRequest request
    ) {
        service.delete(gameId, request == null ? null : request.reason());
    }
}
