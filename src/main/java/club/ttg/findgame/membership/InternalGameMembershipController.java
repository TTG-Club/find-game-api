package club.ttg.findgame.membership;

import club.ttg.findgame.membership.api.GameMembersResponse;
import club.ttg.findgame.membership.api.GameMembershipResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Состав игры для соседних сервисов. Защита — общий секрет
 * {@code X-Service-Token} в {@code InternalServiceTokenFilter}; пользовательского
 * токена здесь нет, поэтому о ком спрашивают, передаётся в пути.
 */
@RestController
@RequestMapping("/api/v1/internal/games/{gameId}/members")
@Tag(name = "Internal: game members")
public class InternalGameMembershipController {

    private final GameMembershipService service;

    public InternalGameMembershipController(GameMembershipService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Мастер и игроки с одобренной заявкой")
    public GameMembersResponse members(@PathVariable UUID gameId) {
        return service.members(gameId);
    }

    @GetMapping("/{userId}")
    @Operation(summary = "Роль пользователя в игре")
    public GameMembershipResponse membership(@PathVariable UUID gameId, @PathVariable UUID userId) {
        return service.membership(gameId, userId);
    }
}
