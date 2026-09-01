package club.ttg.findgame.registration;

import club.ttg.findgame.registration.api.SessionParticipantResponse;
import club.ttg.findgame.registration.api.UpdateAttendanceRequest;
import club.ttg.findgame.registration.api.UpdatePaymentStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Участие в сессии: присутствие и оплата. Состав определяет заявка в игру,
 * поэтому принимать и отклонять здесь нечего.
 */
@RestController
@RequestMapping("/api/v1/games/{gameId}/sessions/{sessionId}/participants")
@Tag(name = "Session participants")
@SecurityRequirement(name = "bearerAuth")
public class SessionParticipantController {

    private final SessionRegistrationService service;

    public SessionParticipantController(SessionRegistrationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Получить состав сессии")
    public List<SessionParticipantResponse> findAllForMaster(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId
    ) {
        return service.findAllForMaster(userId(jwt), gameId, sessionId);
    }

    @GetMapping("/me")
    @Operation(summary = "Получить своё участие в сессии")
    public SessionParticipantResponse findOwn(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @RequestParam(required = false) UUID inviteCode
    ) {
        return service.findOwn(userId(jwt), gameId, sessionId, inviteCode);
    }

    @PatchMapping("/me/attendance")
    @Operation(summary = "Отметить своё присутствие")
    public SessionParticipantResponse updateAttendance(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody UpdateAttendanceRequest request
    ) {
        return service.updateAttendance(userId(jwt), gameId, sessionId, request);
    }

    @PatchMapping("/{playerId}/payment")
    @Operation(summary = "Отметить оплату игрока")
    public SessionParticipantResponse updatePaymentStatus(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @PathVariable UUID playerId,
            @Valid @RequestBody UpdatePaymentStatusRequest request
    ) {
        return service.updatePaymentStatus(userId(jwt), gameId, sessionId, playerId, request);
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
