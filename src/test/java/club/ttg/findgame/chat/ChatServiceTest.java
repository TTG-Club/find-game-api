package club.ttg.findgame.chat;

import club.ttg.findgame.chat.api.CreateChatEventRequest;
import club.ttg.findgame.chat.api.DiceRollRequest;
import club.ttg.findgame.chat.api.SpellCastRequest;
import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistrationStatus;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionRepository;
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
    private GameRepository gameRepository;
    @Mock
    private GameSessionRepository sessionRepository;
    @Mock
    private SessionRegistrationRepository registrationRepository;
    @Mock
    private ChatEventBroadcaster broadcaster;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Test
    void masterSendsTextToGameChat() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        allowMaster(masterId, gameId);
        saveAssignedEvent();

        var response = service().create(masterId, gameId, null,
                new CreateChatEventRequest(UUID.randomUUID(), ChatEventType.TEXT, "  Добрый вечер!  ", null, null));

        assertThat(response.text()).isEqualTo("Добрый вечер!");
        assertThat(response.type()).isEqualTo(ChatEventType.TEXT);
        verify(eventPublisher).publishEvent(any(ChatEventSaved.class));
    }

    @Test
    void approvedPlayerRollsDiceInSessionChat() {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        allowApprovedPlayer(playerId, gameId, sessionId);
        saveAssignedEvent();

        var response = service().create(playerId, gameId, sessionId,
                new CreateChatEventRequest(UUID.randomUUID(), ChatEventType.DICE_ROLL, null,
                        new DiceRollRequest("2d6+3", "Урон"), null));

        assertThat(response.payload().get("results")).hasSize(2);
        assertThat(response.payload().get("total").asInt()).isBetween(5, 15);
        assertThat(response.payload().get("label").stringValue()).isEqualTo("Урон");
    }

    @Test
    void serverRejectsUnsupportedDiceExpression() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        allowMaster(masterId, gameId);

        assertThatThrownBy(() -> service().create(masterId, gameId, null,
                new CreateChatEventRequest(UUID.randomUUID(), ChatEventType.DICE_ROLL, null,
                        new DiceRollRequest("2d20kh1", null), null)))
                .isInstanceOf(InvalidChatEventException.class)
                .hasMessageContaining("Формат броска");
        verify(eventRepository, never()).save(any());
    }

    @Test
    void pendingPlayerCannotReadSessionChat() {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = org.mockito.Mockito.mock(Game.class);
        when(game.getMasterId()).thenReturn(UUID.randomUUID());
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(GameSession.class)));
        when(registrationRepository.existsBySessionIdAndPlayerIdAndStatus(
                sessionId, playerId, SessionRegistrationStatus.APPROVED)).thenReturn(false);

        assertThatThrownBy(() -> service().history(playerId, gameId, sessionId, null, null))
                .isInstanceOf(ChatAccessDeniedException.class);
    }

    @Test
    void spellCastIsStoredAsStructuredPayload() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        allowMaster(masterId, gameId);
        saveAssignedEvent();

        var response = service().create(masterId, gameId, null,
                new CreateChatEventRequest(UUID.randomUUID(), ChatEventType.SPELL_CAST, null, null,
                        new SpellCastRequest("magic-missile", "Волшебная стрела", 1, "Гоблин")));

        assertThat(response.payload().get("spellId").stringValue()).isEqualTo("magic-missile");
        assertThat(response.payload().get("level").asInt()).isEqualTo(1);
        assertThat(response.payload().get("target").stringValue()).isEqualTo("Гоблин");
    }

    private ChatService service() {
        return new ChatService(
                eventRepository, gameRepository, sessionRepository, registrationRepository,
                broadcaster, eventPublisher, objectMapper);
    }

    private void allowMaster(UUID masterId, UUID gameId) {
        Game game = org.mockito.Mockito.mock(Game.class);
        when(game.getMasterId()).thenReturn(masterId);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
    }

    private void allowApprovedPlayer(UUID playerId, UUID gameId, UUID sessionId) {
        Game game = org.mockito.Mockito.mock(Game.class);
        when(game.getMasterId()).thenReturn(UUID.randomUUID());
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(org.mockito.Mockito.mock(GameSession.class)));
        when(registrationRepository.existsBySessionIdAndPlayerIdAndStatus(
                sessionId, playerId, SessionRegistrationStatus.APPROVED)).thenReturn(true);
    }

    private void saveAssignedEvent() {
        when(eventRepository.findByAuthorIdAndClientMessageId(any(), any())).thenReturn(Optional.empty());
        when(eventRepository.save(any(ChatEvent.class))).thenAnswer(invocation -> {
            ChatEvent event = invocation.getArgument(0);
            event.prePersist();
            return event;
        });
    }
}
