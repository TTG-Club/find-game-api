package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.FightStateRequest;
import club.ttg.findgame.nexus.api.FightStateResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Идущий бой комнаты: карусель ходов для тех, кому трекер не открыт. */
@RestController
@RequestMapping("/api/v1/nexuses/{nexusId}/fight")
@Tag(name = "Nexus")
@SecurityRequirement(name = "bearerAuth")
public class NexusFightController {

    private final NexusFightService service;

    public NexusFightController(NexusFightService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Получить снимок идущего боя комнаты")
    public ResponseEntity<FightStateResponse> find(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId
    ) {
        return service.find(userId(jwt), nexusId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping
    @Operation(summary = "Обновить снимок идущего боя комнаты")
    public FightStateResponse publish(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId,
            @Valid @RequestBody FightStateRequest request
    ) {
        return service.publish(userId(jwt), nexusId, request);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
