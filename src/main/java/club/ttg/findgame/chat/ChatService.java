package club.ttg.findgame.chat;

import club.ttg.findgame.chat.api.ChatEventResponse;
import club.ttg.findgame.chat.api.CreateChatEventRequest;
import club.ttg.findgame.chat.api.DiceRollRequest;
import club.ttg.findgame.chat.api.SpellCastRequest;
import club.ttg.findgame.nexus.NexusService;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
public class ChatService {

    private static final int DEFAULT_HISTORY_LIMIT = 50;
    private static final int MAX_HISTORY_LIMIT = 100;

    private final ChatEventRepository eventRepository;
    private final NexusService nexusService;
    private final ChatEventBroadcaster broadcaster;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public ChatService(
            ChatEventRepository eventRepository,
            NexusService nexusService,
            ChatEventBroadcaster broadcaster,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.eventRepository = eventRepository;
        this.nexusService = nexusService;
        this.broadcaster = broadcaster;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Пишет событие в ленту комнаты.
     *
     * Повтор по тому же clientMessageId возвращает уже сохранённое событие:
     * так переотправка после обрыва связи не двоит сообщение.
     *
     * @param requesterId Автор из токена.
     * @param nexusId Комната.
     * @param request Что сказано.
     * @return Сохранённое событие.
     */
    @Transactional
    public ChatEventResponse create(
            UUID requesterId,
            UUID nexusId,
            CreateChatEventRequest request
    ) {
        requireAccess(requesterId, nexusId);
        ChatEventResponse existing = eventRepository
                .findByAuthorIdAndClientMessageId(requesterId, request.clientMessageId())
                .map(this::toResponse)
                .orElse(null);
        if (existing != null) {
            if (!nexusId.equals(existing.nexusId())) {
                throw new InvalidChatEventException("clientMessageId уже использован в другом чате");
            }
            return existing;
        }

        ChatEvent event = new ChatEvent();
        event.setNexusId(nexusId);
        event.setAuthorId(requesterId);
        event.setClientMessageId(request.clientMessageId());
        event.setType(request.type());
        fillContent(event, request);

        ChatEventResponse response = toResponse(eventRepository.save(event));
        eventPublisher.publishEvent(new ChatEventSaved(response));
        return response;
    }

    /**
     * Пишет в чат событие самой игры — старт или завершение сессии.
     *
     * Без проверки доступа: вызывает не участник, а сервис в ответ на
     * действие мастера, и право на это действие уже проверено. Автором
     * ставится мастер: событие вызвано им, и лента остаётся с непустым
     * автором, как того требует хранилище.
     *
     * @param nexusId Комната игры.
     * @param masterId Мастер, чьё действие вызвало событие.
     * @param text Текст события.
     */
    @Transactional
    public void publishSystem(UUID nexusId, UUID masterId, String text) {
        ChatEvent event = new ChatEvent();
        event.setNexusId(nexusId);
        event.setAuthorId(masterId);
        event.setClientMessageId(UUID.randomUUID());
        event.setType(ChatEventType.SYSTEM);
        event.setContent(text);

        eventPublisher.publishEvent(new ChatEventSaved(toResponse(eventRepository.save(event))));
    }

    /**
     * Страница истории комнаты, свежие последними.
     *
     * @param requesterId Читатель из токена.
     * @param nexusId Комната.
     * @param before Курсор: события старше этого момента.
     * @param limit Сколько событий вернуть.
     */
    @Transactional(readOnly = true)
    public List<ChatEventResponse> history(
            UUID requesterId,
            UUID nexusId,
            Instant before,
            Integer limit
    ) {
        requireAccess(requesterId, nexusId);
        int pageSize = limit == null ? DEFAULT_HISTORY_LIMIT : Math.min(limit, MAX_HISTORY_LIMIT);
        if (pageSize < 1) {
            throw new InvalidChatEventException("limit должен быть от 1 до 100");
        }
        Instant cursor = before == null ? Instant.now().plusSeconds(1) : before;
        List<ChatEvent> events = eventRepository
                .findByNexusIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
                        nexusId, cursor, PageRequest.of(0, pageSize));
        List<ChatEventResponse> result = new ArrayList<>(events.stream().map(this::toResponse).toList());
        Collections.reverse(result);
        return result;
    }

    /**
     * Живая лента комнаты.
     *
     * @param requesterId Читатель из токена.
     * @param nexusId Комната.
     */
    @Transactional(readOnly = true)
    public SseEmitter subscribe(UUID requesterId, UUID nexusId) {
        requireAccess(requesterId, nexusId);

        return broadcaster.subscribe(new ChatRoom(nexusId));
    }

    /** В ленту комнаты пускает сама комната: правила доступа живут в ней. */
    private void requireAccess(UUID requesterId, UUID nexusId) {
        if (!nexusService.hasAccess(nexusId, requesterId)) {
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
            case DICE_ROLL -> event.setPayload(toJson(dicePayload(request.diceRoll())));
            case SPELL_CAST -> event.setPayload(toJson(spellPayload(request.spellCast())));
            // Системные события пишет сервис, а не участник.
            case SYSTEM -> throw new InvalidChatEventException(
                    "Событие SYSTEM отправляется сервисом, а не участником");
        }
    }

    /**
     * Складывает присланный бросок в содержимое события.
     *
     * Результат приходит от клиента и принимается как есть: считает его
     * роллер сайта, знающий всю нотацию. Проверяются только границы полей —
     * ими и ограничивается доверие.
     */
    private JsonNode dicePayload(DiceRollRequest request) {
        if (request == null) {
            throw new InvalidChatEventException("Для DICE_ROLL требуется diceRoll");
        }

        ObjectNode payload = objectMapper.createObjectNode();

        putIfNotBlank(payload, "expression", request.expression());

        if (!payload.has("expression")) {
            throw new InvalidChatEventException("Формула броска не может быть пустой");
        }

        payload.put("total", request.total());
        putIfNotBlank(payload, "detail", request.detail());
        putIfNotBlank(payload, "label", request.label());

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

    /** Готовое содержимое строкой: в этом виде оно и лежит в хранилище. */
    private String toJson(JsonNode payload) {
        return payload == null ? null : objectMapper.writeValueAsString(payload);
    }

    /** Содержимое из хранилища; пустое остаётся пустым. */
    private JsonNode fromJson(String payload) {
        return payload == null ? null : objectMapper.readTree(payload);
    }

    private ChatEventResponse toResponse(ChatEvent event) {
        return new ChatEventResponse(
                event.getId(), event.getNexusId(), event.getAuthorId(),
                event.getClientMessageId(), event.getType(), event.getContent(),
                fromJson(event.getPayload()), event.getCreatedAt());
    }
}
