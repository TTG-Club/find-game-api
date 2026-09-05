package club.ttg.findgame.review;

import club.ttg.findgame.review.api.CreateSessionReviewRequest;
import club.ttg.findgame.review.api.ReputationResponse;
import club.ttg.findgame.review.api.SessionReviewResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Взаимные оценки за встречу и репутация участников. */
@RestController
@RequestMapping("/api/v1/games/{gameId}")
@Tag(name = "Review")
@SecurityRequirement(name = "bearerAuth")
public class SessionReviewController {

    private final SessionReviewService service;

    public SessionReviewController(SessionReviewService service) {
        this.service = service;
    }

    @PostMapping("/sessions/{sessionId}/reviews")
    @Operation(summary = "Оценить участника завершённой встречи")
    public SessionReviewResponse review(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody CreateSessionReviewRequest request
    ) {
        return service.review(userId(jwt), gameId, sessionId, request);
    }

    @GetMapping("/sessions/{sessionId}/reviews")
    @Operation(summary = "Получить оценки встречи, видимые смотрящему")
    public List<SessionReviewResponse> findSessionReviews(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId
    ) {
        return service.findSessionReviews(userId(jwt), gameId, sessionId);
    }

    @GetMapping("/players/{playerId}/reputation")
    @Operation(summary = "Репутация игрока для мастера, разбирающего заявку")
    public ReputationResponse getPlayerReputation(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID playerId
    ) {
        return service.getPlayerReputation(userId(jwt), gameId, playerId);
    }

    @GetMapping("/players/{playerId}/reviews")
    @Operation(summary = "Отзывы об игроке для мастера, разбирающего заявку")
    public List<SessionReviewResponse> findPlayerReviews(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID playerId
    ) {
        return service.findPlayerReviews(userId(jwt), gameId, playerId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
