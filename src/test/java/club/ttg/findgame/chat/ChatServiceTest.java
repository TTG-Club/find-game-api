package club.ttg.findgame.chat;

import club.ttg.findgame.chat.api.CreateChatEventRequest;
import club.ttg.findgame.chat.api.DiceRollRequest;
import club.ttg.findgame.chat.api.SpellCastRequest;
import club.ttg.findgame.nexus.NexusService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChatServiceTest {

    @Mock
    private ChatEventRepository eventRepository;

    @Mock
    private NexusService nexusService;

    @Mock
    private ChatEventBroadcaster broadcaster;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void memberSendsTextToTheRoom() {
        UUID authorId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(authorId, nexusId);
        saveAssignedEvent();

        var response = service().create(authorId, nexusId,
                new CreateChatEventRequest(
                        UUID.randomUUID(), ChatEventType.TEXT, "  Добрый вечер!  ", null, null));

        // Текст чистится по краям: отступы в ленте выглядят пустой строкой.
        assertThat(response.text()).isEqualTo("Добрый вечер!");
        assertThat(response.nexusId()).isEqualTo(nexusId);
        assertThat(response.authorId()).isEqualTo(authorId);
        verify(eventPublisher).publishEvent(any(ChatEventSaved.class));
    }

    @Test
    void strangerDoesNotWriteToTheRoom() {
        UUID nexusId = UUID.randomUUID();
        when(nexusService.hasAccess(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service().create(UUID.randomUUID(), nexusId,
                new CreateChatEventRequest(
                        UUID.randomUUID(), ChatEventType.TEXT, "Привет", null, null)))
                .isInstanceOf(ChatAccessDeniedException.class);

        verify(eventRepository, never()).save(any());
    }

    @Test
    void strangerDoesNotReadTheRoom() {
        when(nexusService.hasAccess(any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service()
                .history(UUID.randomUUID(), UUID.randomUUID(), null, null))
                .isInstanceOf(ChatAccessDeniedException.class);
    }

    @Test
    void repeatedSendReturnsTheSameEvent() {
        UUID authorId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        UUID clientMessageId = UUID.randomUUID();
        allow(authorId, nexusId);

        ChatEvent stored = storedText(nexusId, authorId, clientMessageId);

        when(eventRepository.findByAuthorIdAndClientMessageId(authorId, clientMessageId))
                .thenReturn(Optional.of(stored));

        var response = service().create(authorId, nexusId,
                new CreateChatEventRequest(
                        clientMessageId, ChatEventType.TEXT, "Добрый вечер!", null, null));

        // Переотправка после обрыва связи не должна двоить сообщение.
        assertThat(response.id()).isEqualTo(stored.getId());
        verify(eventRepository, never()).save(any());
    }

    @Test
    void sameClientMessageIdInAnotherRoomIsRejected() {
        UUID authorId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        UUID clientMessageId = UUID.randomUUID();
        allow(authorId, nexusId);

        ChatEvent stored = storedText(UUID.randomUUID(), authorId, clientMessageId);

        when(eventRepository.findByAuthorIdAndClientMessageId(authorId, clientMessageId))
                .thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> service().create(authorId, nexusId,
                new CreateChatEventRequest(
                        clientMessageId, ChatEventType.TEXT, "Добрый вечер!", null, null)))
                .isInstanceOf(InvalidChatEventException.class);
    }

    @Test
    void rolledResultIsStoredAsSent() {
        UUID authorId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(authorId, nexusId);
        saveAssignedEvent();

        var response = service().create(authorId, nexusId,
                new CreateChatEventRequest(UUID.randomUUID(), ChatEventType.DICE_ROLL, null,
                        new DiceRollRequest("2к20вл1", 18, "[18, 7] → 18", "Атака"), null));

        // Нотацию сайта разбирает роллер браузера: сервис принимает готовый
        // результат и ничего не пересчитывает.
        assertThat(response.payload().get("expression").stringValue()).isEqualTo("2к20вл1");
        assertThat(response.payload().get("total").asInt()).isEqualTo(18);
        assertThat(response.payload().get("detail").stringValue()).isEqualTo("[18, 7] → 18");
        assertThat(response.payload().get("label").stringValue()).isEqualTo("Атака");
    }

    @Test
    void rollWithoutFormulaIsRejected() {
        UUID authorId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(authorId, nexusId);

        assertThatThrownBy(() -> service().create(authorId, nexusId,
                new CreateChatEventRequest(UUID.randomUUID(), ChatEventType.DICE_ROLL, null,
                        new DiceRollRequest("   ", 5, null, null), null)))
                .isInstanceOf(InvalidChatEventException.class);

        verify(eventRepository, never()).save(any());
    }

    @Test
    void spellCastIsStoredAsStructuredPayload() {
        UUID authorId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(authorId, nexusId);
        saveAssignedEvent();

        var response = service().create(authorId, nexusId,
                new CreateChatEventRequest(UUID.randomUUID(), ChatEventType.SPELL_CAST, null, null,
                        new SpellCastRequest("magic-missile", "Волшебная стрела", 1, "Гоблин")));

        assertThat(response.payload().get("spellId").stringValue()).isEqualTo("magic-missile");
        assertThat(response.payload().get("level").asInt()).isEqualTo(1);
        assertThat(response.payload().get("target").stringValue()).isEqualTo("Гоблин");
    }

    @Test
    void participantDoesNotForgeSystemEvent() {
        UUID authorId = UUID.randomUUID();
        UUID nexusId = UUID.randomUUID();
        allow(authorId, nexusId);

        // Системные отметки пишет сервис: иначе любой сочинил бы «сессия
        // началась» от имени игры.
        assertThatThrownBy(() -> service().create(authorId, nexusId,
                new CreateChatEventRequest(
                        UUID.randomUUID(), ChatEventType.SYSTEM, "Сессия началась", null, null)))
                .isInstanceOf(InvalidChatEventException.class);
    }

    private ChatService service() {
        return new ChatService(
                eventRepository, nexusService, broadcaster, eventPublisher, objectMapper);
    }

    private void allow(UUID userId, UUID nexusId) {
        when(nexusService.hasAccess(nexusId, userId)).thenReturn(true);
    }

    private static ChatEvent storedText(UUID nexusId, UUID authorId, UUID clientMessageId) {
        ChatEvent event = new ChatEvent();

        event.setNexusId(nexusId);
        event.setAuthorId(authorId);
        event.setClientMessageId(clientMessageId);
        event.setType(ChatEventType.TEXT);
        event.setContent("Добрый вечер!");
        event.prePersist();

        return event;
    }

    private void saveAssignedEvent() {
        when(eventRepository.findByAuthorIdAndClientMessageId(any(), any()))
                .thenReturn(Optional.empty());

        when(eventRepository.save(any(ChatEvent.class))).thenAnswer(invocation -> {
            ChatEvent event = invocation.getArgument(0);
            event.prePersist();

            return event;
        });
    }
}
