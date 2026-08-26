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

@RestController
@RequestMapping("/api/v1/games/{gameId}")
@Tag(name = "Game chat")
@SecurityRequirement(name = "bearerAuth")
public class ChatController {

    private final ChatService service;

    public ChatController(ChatService service) {
        this.service = service;
    }

    @PostMapping("/chat/events")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Отправить событие в чат игры")
    public ChatEventResponse createForGame(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @Valid @RequestBody CreateChatEventRequest request
    ) {
        return service.create(userId(jwt), gameId, null, request);
    }

    @GetMapping("/chat/events")
    @Operation(summary = "Получить историю чата игры")
    public List<ChatEventResponse> gameHistory(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @RequestParam(required = false) Instant before,
            @RequestParam(required = false) Integer limit
    ) {
        return service.history(userId(jwt), gameId, null, before, limit);
    }

    @GetMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Подключиться к событиям чата игры")
    public SseEmitter streamGame(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId
    ) {
        return service.subscribe(userId(jwt), gameId, null);
    }

    @PostMapping("/sessions/{sessionId}/chat/events")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Отправить событие в чат сессии")
    public ChatEventResponse createForSession(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @Valid @RequestBody CreateChatEventRequest request
    ) {
        return service.create(userId(jwt), gameId, sessionId, request);
    }

    @GetMapping("/sessions/{sessionId}/chat/events")
    @Operation(summary = "Получить историю чата сессии")
    public List<ChatEventResponse> sessionHistory(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId,
            @RequestParam(required = false) Instant before,
            @RequestParam(required = false) Integer limit
    ) {
        return service.history(userId(jwt), gameId, sessionId, before, limit);
    }

    @GetMapping(value = "/sessions/{sessionId}/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Подключиться к событиям чата сессии")
    public SseEmitter streamSession(
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID gameId,
            @PathVariable UUID sessionId
    ) {
        return service.subscribe(userId(jwt), gameId, sessionId);
    }

    private UUID userId(Jwt jwt) {
        return UUID.fromString(jwt.getSubject());
    }
}
