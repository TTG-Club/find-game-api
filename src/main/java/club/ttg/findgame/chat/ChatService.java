package club.ttg.findgame.chat;

import club.ttg.findgame.chat.api.ChatEventResponse;
import club.ttg.findgame.chat.api.CreateChatEventRequest;
import club.ttg.findgame.chat.api.DiceRollRequest;
import club.ttg.findgame.chat.api.SpellCastRequest;
import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistrationStatus;
import club.ttg.findgame.session.GameSessionNotFoundException;
import club.ttg.findgame.session.GameSessionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ChatService {

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 100;
    private static final int MAX_DICE_COUNT = 100;
    private static final int MAX_DIE_SIDES = 1000;
    private static final Pattern DICE_EXPRESSION = Pattern.compile("^(\\d{1,3})d(\\d{1,4})([+-]\\d{1,4})?$", Pattern.CASE_INSENSITIVE);

    private final ChatEventRepository eventRepository;
    private final GameRepository gameRepository;
    private final GameSessionRepository sessionRepository;
    private final SessionRegistrationRepository registrationRepository;
    private final ChatEventBroadcaster broadcaster;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public ChatService(
            ChatEventRepository eventRepository,
            GameRepository gameRepository,
            GameSessionRepository sessionRepository,
            SessionRegistrationRepository registrationRepository,
            ChatEventBroadcaster broadcaster,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.eventRepository = eventRepository;
        this.gameRepository = gameRepository;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.broadcaster = broadcaster;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ChatEventResponse create(
            UUID requesterId,
            UUID gameId,
            UUID sessionId,
            CreateChatEventRequest request
    ) {
        requireAccess(requesterId, gameId, sessionId);
        ChatEventResponse existing = eventRepository
                .findByAuthorIdAndClientMessageId(requesterId, request.clientMessageId())
                .map(this::toResponse)
                .orElse(null);
        if (existing != null) {
            if (!existing.gameId().equals(gameId) || !java.util.Objects.equals(existing.sessionId(), sessionId)) {
                throw new InvalidChatEventException("clientMessageId уже использован в другом чате");
            }
            return existing;
        }

        ChatEvent event = new ChatEvent();
        event.setGameId(gameId);
        event.setSessionId(sessionId);
        event.setAuthorId(requesterId);
        event.setClientMessageId(request.clientMessageId());
        event.setType(request.type());
        fillContent(event, request);

        ChatEventResponse response = toResponse(eventRepository.save(event));
        eventPublisher.publishEvent(new ChatEventSaved(response));
        return response;
    }

    @Transactional(readOnly = true)
    public List<ChatEventResponse> history(
            UUID requesterId,
            UUID gameId,
            UUID sessionId,
            Instant before,
            Integer limit
    ) {
        requireAccess(requesterId, gameId, sessionId);
        int pageSize = limit == null ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        if (pageSize < 1) {
            throw new InvalidChatEventException("limit должен быть от 1 до 100");
        }
        Instant cursor = before == null ? Instant.now().plusSeconds(1) : before;
        List<ChatEvent> events = sessionId == null
                ? eventRepository.findByGameIdAndSessionIdIsNullAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
                        gameId, cursor, PageRequest.of(0, pageSize))
                : eventRepository.findByGameIdAndSessionIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
                        gameId, sessionId, cursor, PageRequest.of(0, pageSize));
        List<ChatEventResponse> result = new ArrayList<>(events.stream().map(this::toResponse).toList());
        Collections.reverse(result);
        return result;
    }

    @Transactional(readOnly = true)
    public SseEmitter subscribe(UUID requesterId, UUID gameId, UUID sessionId) {
        requireAccess(requesterId, gameId, sessionId);
        return broadcaster.subscribe(new ChatRoom(gameId, sessionId));
    }

    private void requireAccess(UUID requesterId, UUID gameId, UUID sessionId) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (sessionId != null && sessionRepository.findByIdAndGameId(sessionId, gameId).isEmpty()) {
            throw new GameSessionNotFoundException(sessionId);
        }
        if (game.getMasterId().equals(requesterId)) {
            return;
        }
        boolean approved = sessionId == null
                ? registrationRepository.existsApprovedPlayerInGame(
                        gameId, requesterId, SessionRegistrationStatus.APPROVED)
                : registrationRepository.existsBySessionIdAndPlayerIdAndStatus(
                        sessionId, requesterId, SessionRegistrationStatus.APPROVED);
        if (!approved) {
            throw new ChatAccessDeniedException();
        }
    }

    private void fillContent(ChatEvent event, CreateChatEventRequest request) {
        switch (request.type()) {
            case TEXT -> {
                String text = normalize(request.text());
                if (text == null) {
                    throw new InvalidChatEventException("Текст сообщения не может быть пустым");
                }
                event.setContent(text);
            }
            case DICE_ROLL -> event.setPayload(rollDice(request.diceRoll()));
            case SPELL_CAST -> event.setPayload(spellPayload(request.spellCast()));
        }
    }

    private JsonNode rollDice(DiceRollRequest request) {
        if (request == null) {
            throw new InvalidChatEventException("Для DICE_ROLL требуется diceRoll");
        }
        String expression = request.expression().replace(" ", "");
        Matcher matcher = DICE_EXPRESSION.matcher(expression);
        if (!matcher.matches()) {
            throw new InvalidChatEventException("Формат броска: NdM, NdM+K или NdM-K");
        }
        int count = Integer.parseInt(matcher.group(1));
        int sides = Integer.parseInt(matcher.group(2));
        int modifier = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        if (count < 1 || count > MAX_DICE_COUNT || sides < 2 || sides > MAX_DIE_SIDES) {
            throw new InvalidChatEventException("Допустимо от 1 до 100 кубов с количеством граней от 2 до 1000");
        }

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("expression", expression.toLowerCase());
        var results = payload.putArray("results");
        int total = modifier;
        for (int i = 0; i < count; i++) {
            int value = random.nextInt(sides) + 1;
            results.add(value);
            total += value;
        }
        payload.put("modifier", modifier);
        payload.put("total", total);
        String label = normalize(request.label());
        if (label != null) {
            payload.put("label", label);
        }
        return payload;
    }

    private JsonNode spellPayload(SpellCastRequest request) {
        if (request == null) {
            throw new InvalidChatEventException("Для SPELL_CAST требуется spellCast");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        putIfNotBlank(payload, "spellId", request.spellId());
        putIfNotBlank(payload, "name", request.name());
        if (!payload.has("name")) {
            throw new InvalidChatEventException("Название заклинания не может быть пустым");
        }
        if (request.level() != null) {
            if (request.level() < 0 || request.level() > 9) {
                throw new InvalidChatEventException("Уровень заклинания должен быть от 0 до 9");
            }
            payload.put("level", request.level());
        }
        putIfNotBlank(payload, "target", request.target());
        return payload;
    }

    private void putIfNotBlank(ObjectNode payload, String field, String value) {
        String normalized = normalize(value);
        if (normalized != null) {
            payload.put(field, normalized);
        }
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private ChatEventResponse toResponse(ChatEvent event) {
        return new ChatEventResponse(
                event.getId(), event.getGameId(), event.getSessionId(), event.getAuthorId(),
                event.getClientMessageId(), event.getType(), event.getContent(), event.getPayload(), event.getCreatedAt());
    }
}
