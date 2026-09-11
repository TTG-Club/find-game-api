package club.ttg.findgame.favorite;

import club.ttg.findgame.favorite.api.FavoriteGameResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Избранные игры: личные закладки на объявления. */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Favorite")
@SecurityRequirement(name = "bearerAuth")
public class FavoriteGameController {

    private final FavoriteGameService service;

    public FavoriteGameController(FavoriteGameService service) {
        this.service = service;
    }

    @PutMapping("/games/{gameId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Отложить игру в избранное")
    public void addFavorite(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId
    ) {
        service.addFavorite(userId(jwt), gameId);
    }

    @DeleteMapping("/games/{gameId}/favorite")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Убрать игру из избранного")
    public void removeFavorite(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId
    ) {
        service.removeFavorite(userId(jwt), gameId);
    }

    @GetMapping("/profiles/me/favorites/games")
    @Operation(summary = "Отмеченные игры")
    public List<FavoriteGameResponse> findFavorites(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt
    ) {
        return service.findFavorites(userId(jwt));
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
