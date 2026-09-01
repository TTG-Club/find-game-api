package club.ttg.findgame.registration;

import club.ttg.findgame.registration.api.CreateGameRegistrationRequest;
import club.ttg.findgame.registration.api.GameRegistrationResponse;
import club.ttg.findgame.registration.api.ReviewGameRegistrationRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Заявки в игру. Игрок записывается в игру целиком: принятый попадает во все
 * её запланированные сессии.
 */
@RestController
@RequestMapping("/api/v1/games/{gameId}/registrations")
@Tag(name = "Game registrations")
@SecurityRequirement(name = "bearerAuth")
public class GameRegistrationController {

    private final GameRegistrationService service;

    public GameRegistrationController(GameRegistrationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Подать заявку в игру")
    public GameRegistrationResponse register(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @RequestParam(required = false) UUID inviteCode,
            @Valid @RequestBody CreateGameRegistrationRequest request
    ) {
        return service.register(userId(jwt), gameId, inviteCode, request);
    }

    @GetMapping
    @Operation(summary = "Получить заявки игры")
    public List<GameRegistrationResponse> findAllForMaster(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId
    ) {
        return service.findAllForMaster(userId(jwt), gameId);
    }

    @GetMapping("/me")
    @Operation(summary = "Получить свою заявку")
    public GameRegistrationResponse findOwn(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @RequestParam(required = false) UUID inviteCode
    ) {
        return service.findOwn(userId(jwt), gameId, inviteCode);
    }

    @DeleteMapping("/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отозвать свою заявку")
    public void withdraw(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId
    ) {
        service.withdraw(userId(jwt), gameId);
    }

    @PatchMapping("/{registrationId}")
    @Operation(summary = "Принять или отклонить заявку")
    public GameRegistrationResponse review(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID registrationId,
            @Valid @RequestBody ReviewGameRegistrationRequest request
    ) {
        return service.review(userId(jwt), gameId, registrationId, request);
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
