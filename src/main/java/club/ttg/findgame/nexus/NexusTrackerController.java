package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.AddNexusTrackerRequest;
import club.ttg.findgame.nexus.api.NexusTrackerResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Трекеры инициативы комнаты. */
@RestController
@RequestMapping("/api/v1/nexuses/{nexusId}/trackers")
@Tag(name = "Nexus")
@SecurityRequirement(name = "bearerAuth")
public class NexusTrackerController {

    private final NexusTrackerService service;

    public NexusTrackerController(NexusTrackerService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Получить трекеры инициативы комнаты")
    public List<NexusTrackerResponse> findAll(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId
    ) {
        return service.findAll(userId(jwt), nexusId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Завести трекер инициативы в комнате")
    public NexusTrackerResponse add(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId,
            @Valid @RequestBody AddNexusTrackerRequest request
    ) {
        return service.add(userId(jwt), nexusId, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Убрать трекер инициативы из комнаты")
    public void remove(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId,
            @PathVariable UUID id
    ) {
        service.remove(userId(jwt), nexusId, id);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
