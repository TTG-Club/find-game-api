package club.ttg.findgame.registration;

import club.ttg.findgame.registration.api.CreateSessionRegistrationRequest;
import club.ttg.findgame.registration.api.ReviewSessionRegistrationRequest;
import club.ttg.findgame.registration.api.SessionRegistrationResponse;
import club.ttg.findgame.registration.api.UpdateAttendanceRequest;
import club.ttg.findgame.registration.api.UpdatePaymentStatusRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/games/{gameId}/sessions/{sessionId}/registrations")
@Tag(name = "Session registrations")
@SecurityRequirement(name = "bearerAuth")
public class SessionRegistrationController {

    private final SessionRegistrationService service;

    public SessionRegistrationController(SessionRegistrationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Подать заявку на участие в сессии")
    public SessionRegistrationResponse register(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @RequestParam(required = false) UUID inviteCode,
            @Valid @RequestBody CreateSessionRegistrationRequest request
    ) {
        return service.register(UUID.fromString(jwt.getSubject()), gameId, sessionId, inviteCode, request);
    }

    @GetMapping
    @Operation(summary = "Получить заявки на сессию (только мастер)")
    public List<SessionRegistrationResponse> findAllForMaster(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId
    ) {
        return service.findAllForMaster(UUID.fromString(jwt.getSubject()), gameId, sessionId);
    }

    @PatchMapping("/{registrationId}")
    @Operation(summary = "Принять или отклонить заявку (только мастер)")
    public SessionRegistrationResponse review(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @PathVariable UUID registrationId,
            @Valid @RequestBody ReviewSessionRegistrationRequest request
    ) {
        return service.review(
                UUID.fromString(jwt.getSubject()), gameId, sessionId, registrationId, request);
    }

    @PatchMapping("/me/attendance")
    @Operation(summary = "Изменить свой статус присутствия после принятия заявки")
    public SessionRegistrationResponse updateAttendance(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody UpdateAttendanceRequest request
    ) {
        return service.updateAttendance(
                UUID.fromString(jwt.getSubject()), gameId, sessionId, request);
    }

    @PatchMapping("/{registrationId}/payment")
    @Operation(summary = "Отметить оплату игрока в платной сессии (только мастер)")
    public SessionRegistrationResponse updatePaymentStatus(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @PathVariable UUID registrationId,
            @Valid @RequestBody UpdatePaymentStatusRequest request
    ) {
        return service.updatePaymentStatus(
                UUID.fromString(jwt.getSubject()), gameId, sessionId, registrationId, request);
    }
}
