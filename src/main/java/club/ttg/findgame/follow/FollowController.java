package club.ttg.findgame.follow;

import club.ttg.findgame.follow.api.CreateGameInviteRequest;
import club.ttg.findgame.follow.api.FollowResponse;
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
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Отметки участников друг о друге и приглашения в игру. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Follow")
@SecurityRequirement(name = "bearerAuth")
public class FollowController {

    private final FollowService service;

    public FollowController(FollowService service) {
        this.service = service;
    }

    @PutMapping("/profiles/masters/{masterId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отметить мастера, чтобы видеть его новые игры")
    public void followMaster(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID masterId
    ) {
        service.followMaster(userId(jwt), masterId);
    }

    @DeleteMapping("/profiles/masters/{masterId}/follow")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Снять отметку с мастера")
    public void unfollowMaster(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID masterId
    ) {
        service.unfollowMaster(userId(jwt), masterId);
    }

    @PutMapping("/profiles/players/{playerId}/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отметить игрока, чтобы звать его в свои игры")
    public void bookmarkPlayer(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID playerId
    ) {
        service.bookmarkPlayer(userId(jwt), playerId);
    }

    @DeleteMapping("/profiles/players/{playerId}/bookmark")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Снять отметку с игрока")
    public void unbookmarkPlayer(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID playerId
    ) {
        service.unbookmarkPlayer(userId(jwt), playerId);
    }

    @GetMapping("/profiles/me/follows/masters")
    @Operation(summary = "Отмеченные мастера")
    public List<FollowResponse> findFollowedMasters(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt
    ) {
        return service.findFollowedMasters(userId(jwt));
    }

    @GetMapping("/profiles/me/follows/players")
    @Operation(summary = "Отмеченные игроки")
    public List<FollowResponse> findBookmarkedPlayers(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt
    ) {
        return service.findBookmarkedPlayers(userId(jwt));
    }

    @PostMapping("/games/{gameId}/invites")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Позвать отмеченного игрока в свою игру")
    public void invitePlayer(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @Valid @RequestBody CreateGameInviteRequest request
    ) {
        service.invitePlayer(userId(jwt), gameId, request.playerId());
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
