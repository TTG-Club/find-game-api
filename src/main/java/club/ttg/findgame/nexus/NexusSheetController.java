package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.AddNexusSheetRequest;
import club.ttg.findgame.nexus.api.NexusSheetResponse;
import club.ttg.findgame.nexus.api.TransferNexusSheetRequest;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Листы персонажей, выложенные в комнату. */
@RestController
@RequestMapping("/api/v1/nexuses/{nexusId}/sheets")
@Tag(name = "Nexus")
@SecurityRequirement(name = "bearerAuth")
public class NexusSheetController {

    private final NexusSheetService service;

    public NexusSheetController(NexusSheetService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Получить листы персонажей комнаты")
    public List<NexusSheetResponse> findAll(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId
    ) {
        return service.findAll(userId(jwt), nexusId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Выложить лист персонажа в комнату")
    public NexusSheetResponse add(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId,
            @Valid @RequestBody AddNexusSheetRequest request
    ) {
        return service.add(userId(jwt), nexusId, request);
    }

    @PatchMapping("/{sheetId}/owner")
    @Operation(summary = "Передать лист персонажа другому участнику")
    public NexusSheetResponse transfer(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId,
            @PathVariable UUID sheetId,
            @Valid @RequestBody TransferNexusSheetRequest request
    ) {
        return service.transfer(userId(jwt), nexusId, sheetId, request.ownerId());
    }

    @DeleteMapping("/{sheetId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Убрать лист персонажа из комнаты")
    public void remove(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId,
            @PathVariable UUID sheetId
    ) {
        service.remove(userId(jwt), nexusId, sheetId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
