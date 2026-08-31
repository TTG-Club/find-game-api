package club.ttg.findgame.notification;

import club.ttg.findgame.notification.api.NotificationResponse;
import club.ttg.findgame.notification.api.UnreadNotificationsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@Tag(name = "Notifications")
@SecurityRequirement(name = "bearerAuth")
@Validated
public class NotificationController {

    private final NotificationService service;

    public NotificationController(NotificationService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Лента уведомлений текущего пользователя")
    public Page<NotificationResponse> find(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return service.find(UUID.fromString(jwt.getSubject()), page, size);
    }

    @GetMapping("/unread-count")
    @Operation(summary = "Сколько уведомлений не прочитано")
    public UnreadNotificationsResponse countUnread(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt
    ) {
        return new UnreadNotificationsResponse(
                service.countUnread(UUID.fromString(jwt.getSubject())));
    }

    @PatchMapping("/{notificationId}/read")
    @Operation(summary = "Отметить уведомление прочитанным")
    public NotificationResponse markRead(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID notificationId
    ) {
        return service.markRead(UUID.fromString(jwt.getSubject()), notificationId);
    }

    @PatchMapping("/read")
    @Operation(summary = "Отметить прочитанной всю ленту")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markAllRead(@Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt) {
        service.markAllRead(UUID.fromString(jwt.getSubject()));
    }
}
