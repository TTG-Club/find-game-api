package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.CreateNexusRequest;
import club.ttg.findgame.nexus.api.NexusMemberResponse;
import club.ttg.findgame.nexus.api.NexusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Validated
@RestController
@RequestMapping("/api/v1/nexuses")
@Tag(name = "Nexus")
@SecurityRequirement(name = "bearerAuth")
public class NexusController {

    private final NexusService service;

    public NexusController(NexusService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Создать игровую комнату")
    public NexusResponse create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody CreateNexusRequest request
    ) {
        return service.create(UUID.fromString(jwt.getSubject()), request);
    }

    @GetMapping
    @Operation(summary = "Получить свои комнаты: созданные и те, куда позвали")
    public Page<NexusResponse> findAvailable(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.findAvailable(UUID.fromString(jwt.getSubject()), page, size);
    }

    @GetMapping("/{nexusId}")
    @Operation(summary = "Открыть комнату")
    public NexusResponse get(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId
    ) {
        return service.get(UUID.fromString(jwt.getSubject()), nexusId);
    }

    @PostMapping("/join/{inviteCode}")
    @Operation(summary = "Войти в комнату по ссылке-приглашению")
    public NexusResponse join(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID inviteCode
    ) {
        return service.joinByInvite(UUID.fromString(jwt.getSubject()), inviteCode);
    }

    @GetMapping("/{nexusId}/members")
    @Operation(summary = "Получить состав комнаты")
    public List<NexusMemberResponse> findMembers(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId
    ) {
        return service.findMembers(UUID.fromString(jwt.getSubject()), nexusId);
    }

    @DeleteMapping("/{nexusId}/members/{memberId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Вывести участника из комнаты или выйти самому")
    public void removeMember(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId,
            @PathVariable UUID memberId
    ) {
        service.removeMember(UUID.fromString(jwt.getSubject()), nexusId, memberId);
    }
}
