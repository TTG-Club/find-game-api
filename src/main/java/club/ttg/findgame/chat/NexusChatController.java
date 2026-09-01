package club.ttg.findgame.chat;

import club.ttg.findgame.chat.api.ChatEventResponse;
import club.ttg.findgame.chat.api.CreateChatEventRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Чат комнаты. Лента одна на нексус: и разговор группы, и события игры идут
 * в неё же.
 */
@RestController
@RequestMapping("/api/v1/nexuses/{nexusId}/chat")
@Tag(name = "Nexus chat")
@SecurityRequirement(name = "bearerAuth")
public class NexusChatController {

    private final ChatService service;

    public NexusChatController(ChatService service) {
        this.service = service;
    }

    @PostMapping("/events")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Отправить событие в чат комнаты")
    public ChatEventResponse create(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId,
            @Valid @RequestBody CreateChatEventRequest request
    ) {
        return service.create(userId(jwt), nexusId, request);
    }

    @GetMapping("/events")
    @Operation(summary = "Получить историю чата комнаты")
    public List<ChatEventResponse> history(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId,
            @RequestParam(required = false) Instant before,
            @RequestParam(required = false) Integer limit
    ) {
        return service.history(userId(jwt), nexusId, before, limit);
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Подписаться на живую ленту комнаты")
    public SseEmitter stream(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID nexusId
    ) {
        return service.subscribe(userId(jwt), nexusId);
    }

    private static UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
