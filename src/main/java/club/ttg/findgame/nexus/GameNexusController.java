package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.NexusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Комната игры. Живёт под адресом игры, а не среди прочих комнат: попасть в
 * неё можно только отсюда, и в общем списке её нет.
 */
@RestController
@RequestMapping("/api/v1/games/{gameId}/nexus")
@Tag(name = "Nexus")
@SecurityRequirement(name = "bearerAuth")
public class GameNexusController {

    private final NexusService service;

    public GameNexusController(NexusService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Открыть комнату игры: мастеру и подавшим заявку")
    public NexusResponse get(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId
    ) {
        return service.getForGame(UUID.fromString(jwt.getSubject()), gameId);
    }
}
