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
    private final NexusService nexusService;
    private final ChatEventBroadcaster broadcaster;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

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
            case DICE_ROLL -> event.setPayload(rollDice(request.diceRoll()));
            case SPELL_CAST -> event.setPayload(spellPayload(request.spellCast()));
            // Системные события пишет сервис, а не участник.
            case SYSTEM -> throw new InvalidChatEventException(
                    "Событие SYSTEM отправляется сервисом, а не участником");
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
                event.getId(), event.getNexusId(), event.getAuthorId(),
                event.getClientMessageId(), event.getType(), event.getContent(),
                event.getPayload(), event.getCreatedAt());
    }
}
