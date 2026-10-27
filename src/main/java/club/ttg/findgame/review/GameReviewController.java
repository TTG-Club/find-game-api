package club.ttg.findgame.review;

import club.ttg.findgame.review.api.CreateGameReviewRequest;
import club.ttg.findgame.review.api.GameReviewResponse;
import club.ttg.findgame.review.api.PendingReviewResponse;
import club.ttg.findgame.review.api.ReputationResponse;
import club.ttg.findgame.review.api.ReviewTargetResponse;
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

/** Взаимные оценки за игру и репутация участников. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Review")
@SecurityRequirement(name = "bearerAuth")
public class GameReviewController {

    private final GameReviewService service;

    public GameReviewController(GameReviewService service) {
        this.service = service;
    }

    @PostMapping("/games/{gameId}/reviews")
    @Operation(summary = "Оценить мастера или игрока, с которым играл")
    public GameReviewResponse review(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @Valid @RequestBody CreateGameReviewRequest request
    ) {
        return service.review(userId(jwt), gameId, request);
    }

    @GetMapping("/games/{gameId}/reviews")
    @Operation(summary = "Получить, кого смотрящий вправе оценить в игре, со своими оценками")
    public List<ReviewTargetResponse> findTargets(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId
    ) {
        return service.findTargets(userId(jwt), gameId);
    }

    @GetMapping("/reviews/pending")
    @Operation(summary = "Получить игры, где смотрящий ещё не оценил тех, с кем играл")
    public List<PendingReviewResponse> findPending(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt
    ) {
        return service.findPending(userId(jwt));
    }

    @GetMapping("/games/{gameId}/players/{playerId}/reputation")
    @Operation(summary = "Репутация игрока для мастера, разбирающего заявку")
    public ReputationResponse getPlayerReputation(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID playerId
    ) {
        return service.getPlayerReputation(userId(jwt), gameId, playerId);
    }

    @GetMapping("/games/{gameId}/players/{playerId}/reviews")
    @Operation(summary = "Отзывы об игроке для мастера, разбирающего заявку")
    public List<GameReviewResponse> findPlayerReviews(
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
