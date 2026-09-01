package club.ttg.findgame.session;

import club.ttg.findgame.chat.ChatService;
import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistration;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.registration.SessionAttendanceStatus;
import club.ttg.findgame.session.api.CreateGameSessionRequest;
import club.ttg.findgame.session.api.CopyGameSessionRequest;
import club.ttg.findgame.session.api.GameSessionResponse;
import club.ttg.findgame.session.api.ScheduleGameSessionRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameSessionServiceTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSessionRepository sessionRepository;

    @Mock
    private SessionRegistrationRepository registrationRepository;

    @Mock
    private GameRegistrationRepository gameRegistrationRepository;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ChatService chatService;

    private final GameSessionMapper mapper = Mappers.getMapper(GameSessionMapper.class);

    @Test
    void ownerCreatesScheduledSessionWithEmptyPlayerList() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = game(masterId, GameCostType.PAID);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(java.util.Optional.of(game));
        when(sessionRepository.save(any(GameSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GameSessionResponse response = service().create(masterId, gameId, request());

        assertThat(response.gameId()).isEqualTo(gameId);
        assertThat(response.title()).isEqualTo("Первая глава");
        assertThat(response.estimatedDurationMinutes()).isEqualTo(240);
        assertThat(response.status()).isEqualTo(GameSessionStatus.SCHEDULED);
        assertThat(response.priceAmount()).isEqualByComparingTo("15.00");
        assertThat(response.priceCurrency()).isEqualTo("EUR");
        assertThat(response.paymentType()).isEqualTo(SessionPaymentType.PREPAYMENT);
        assertThat(response.registeredPlayerIds()).isEmpty();
    }

    @Test
    void nonOwnerCannotCreateSession() {
        UUID gameId = UUID.randomUUID();
        Game game = game(UUID.randomUUID(), null);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(java.util.Optional.of(game));

        assertThatThrownBy(() -> service().create(UUID.randomUUID(), gameId, request()))
                .isInstanceOf(GameSessionAccessDeniedException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void oneShotCanHaveMultipleSessions() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = game(masterId, GameCostType.PAID);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(java.util.Optional.of(game));
        when(sessionRepository.save(any(GameSession.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GameSessionResponse response = service().create(masterId, gameId, request());

        assertThat(response.gameId()).isEqualTo(gameId);
        verify(sessionRepository).save(any(GameSession.class));
    }

    @Test
    void paidGameRejectsHalfFilledCostOnSession() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = game(masterId, GameCostType.PAID);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(java.util.Optional.of(game));
        CreateGameSessionRequest request = new CreateGameSessionRequest(
                "Первая глава", Instant.parse("2099-01-10T18:00:00Z"), 240,
                new java.math.BigDecimal("15.00"), null, null);

        // Сумма без валюты игроку ничего не говорит.
        assertThatThrownBy(() -> service().create(masterId, gameId, request))
                .isInstanceOf(InvalidGameSessionCostException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void paidGameAllowsFreeSession() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = game(masterId, GameCostType.PAID);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(java.util.Optional.of(game));
        when(sessionRepository.save(any(GameSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        CreateGameSessionRequest request = new CreateGameSessionRequest(
                "Знакомство", Instant.parse("2099-01-10T18:00:00Z"), 240, null, null, null);

        // Платная игра не обязана быть платной целиком: знакомство или
        // отработку мастер вправе провести бесплатно.
        GameSessionResponse response = service().create(masterId, gameId, request);

        assertThat(response.priceAmount()).isNull();
        assertThat(response.paymentType()).isNull();
    }

    @Test
    void freeGameRejectsCostOnSession() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = game(masterId, GameCostType.FREE);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(java.util.Optional.of(game));

        assertThatThrownBy(() -> service().create(masterId, gameId, request()))
                .isInstanceOf(InvalidGameSessionCostException.class);
        verify(sessionRepository, never()).save(any());
    }

    @Test
    void freeGameCreatesSessionWithoutCost() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = game(masterId, GameCostType.FREE);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(java.util.Optional.of(game));
        when(sessionRepository.save(any(GameSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreateGameSessionRequest request = new CreateGameSessionRequest(
                "Первая глава", Instant.parse("2099-01-10T18:00:00Z"), 240, null, null, null);

        GameSessionResponse response = service().create(masterId, gameId, request);

        assertThat(response.priceAmount()).isNull();
        assertThat(response.priceCurrency()).isNull();
        assertThat(response.paymentType()).isNull();
    }

    @Test
    void sessionResponseContainsOnlyApprovedPlayers() {
        UUID requesterId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID approvedPlayerId = UUID.randomUUID();
        Game game = mock(Game.class);
        GameSession session = mock(GameSession.class);
        SessionRegistration registration = mock(SessionRegistration.class);
        when(game.getMasterId()).thenReturn(masterId);
        when(game.getVisibility()).thenReturn(club.ttg.findgame.game.GameVisibility.PUBLIC);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(session.getId()).thenReturn(sessionId);
        when(sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId)).thenReturn(List.of(session));
        when(registrationRepository.findAllBySessionIdIn(List.of(sessionId)))
                .thenReturn(List.of(registration));
        when(registration.getSessionId()).thenReturn(sessionId);
        when(registration.getPlayerId()).thenReturn(approvedPlayerId);

        List<GameSessionResponse> responses = service().findByGame(requesterId, gameId, null);

        assertThat(responses.getFirst().registeredPlayerIds()).containsExactly(approvedPlayerId);
    }

    @Test
    void ownerCopiesCampaignSessionWithApprovedPlayers() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sourceSessionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        Game game = game(masterId, null);
        GameSession source = new GameSession();
        source.setId(sourceSessionId);
        source.setGameId(gameId);
        source.setTitle("Вторая глава");
        source.setStartsAt(Instant.parse("2099-01-10T18:00:00Z"));
        source.setEstimatedDurationMinutes(240);
        source.setStatus(GameSessionStatus.COMPLETED);
        source.setPriceAmount(new BigDecimal("20.00"));
        source.setPriceCurrency("EUR");
        source.setPaymentType(SessionPaymentType.POSTPAYMENT);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sourceSessionId, gameId)).thenReturn(Optional.of(source));
        when(sessionRepository.save(any(GameSession.class))).thenAnswer(invocation -> {
            GameSession saved = invocation.getArgument(0);
            saved.prePersist();
            return saved;
        });
        GameRegistration approved = mock(GameRegistration.class);
        when(approved.getPlayerId()).thenReturn(playerId);
        when(gameRegistrationRepository.findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED))
                .thenReturn(List.of(approved));
        Instant newStart = Instant.parse("2099-01-17T18:00:00Z");

        GameSessionResponse response = service().copy(
                masterId, gameId, sourceSessionId, new CopyGameSessionRequest(null, newStart));

        assertThat(response.id()).isNotEqualTo(sourceSessionId);
        assertThat(response.title()).isEqualTo("Вторая глава");
        assertThat(response.startsAt()).isEqualTo(newStart);
        assertThat(response.estimatedDurationMinutes()).isEqualTo(240);
        assertThat(response.status()).isEqualTo(GameSessionStatus.SCHEDULED);
        assertThat(response.priceAmount()).isEqualByComparingTo("20.00");
        assertThat(response.paymentType()).isEqualTo(SessionPaymentType.POSTPAYMENT);
        assertThat(response.registeredPlayerIds()).containsExactly(playerId);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<SessionRegistration>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(registrationRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(copy -> {
            assertThat(copy.getSessionId()).isEqualTo(response.id());
            assertThat(copy.getPlayerId()).isEqualTo(playerId);
            assertThat(copy.getAttendanceStatus()).isEqualTo(SessionAttendanceStatus.NOT_ATTENDING);
            assertThat(copy.getPaidAt()).isNull();
        });
    }

    @Test
    void oneShotSessionCanBeCopied() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sourceSessionId = UUID.randomUUID();
        Game game = game(masterId, null);
        GameSession source = new GameSession();
        source.setId(sourceSessionId);
        source.setGameId(gameId);
        source.setTitle("Нулевая сессия");
        source.setStartsAt(Instant.parse("2099-01-10T18:00:00Z"));
        source.setStatus(GameSessionStatus.COMPLETED);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sourceSessionId, gameId)).thenReturn(Optional.of(source));
        when(sessionRepository.save(any(GameSession.class))).thenAnswer(invocation -> {
            GameSession saved = invocation.getArgument(0);
            saved.prePersist();
            return saved;
        });
        when(gameRegistrationRepository.findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED))
                .thenReturn(List.of());

        GameSessionResponse response = service().copy(
                masterId,
                gameId,
                sourceSessionId,
                new CopyGameSessionRequest("Основная сессия", Instant.parse("2099-01-17T18:00:00Z")));

        assertThat(response.title()).isEqualTo("Основная сессия");
        assertThat(response.status()).isEqualTo(GameSessionStatus.SCHEDULED);
        verify(sessionRepository).save(any(GameSession.class));
    }

    @Test
    void sessionCanBeCreatedWithOpenDate() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = game(masterId, GameCostType.FREE);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.save(any(GameSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // Набор с открытой датой: мастер объявляет сессию, а время назначит,
        // когда соберёт игроков.
        GameSessionResponse response = service().create(
                masterId, gameId, freeRequest(null));

        assertThat(response.startsAt()).isNull();
        assertThat(response.status()).isEqualTo(GameSessionStatus.SCHEDULED);
    }

    @Test
    void masterSchedulesOpenDateSession() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession();
        Game game = game(masterId, null);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any(GameSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        Instant startsAt = Instant.parse("2099-02-01T18:00:00Z");

        GameSessionResponse response = service().schedule(
                masterId, gameId, sessionId, new ScheduleGameSessionRequest(startsAt));

        assertThat(response.startsAt()).isEqualTo(startsAt);
    }

    @Test
    void alreadyScheduledSessionIsNotRescheduled() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession();
        session.setStartsAt(Instant.parse("2099-01-10T18:00:00Z"));
        Game game = game(masterId, null);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));

        // Игроки уже подстроились под объявленное время: тихий перенос их бы подвёл.
        assertThatThrownBy(() -> service().schedule(
                masterId, gameId, sessionId,
                new ScheduleGameSessionRequest(Instant.parse("2099-03-01T18:00:00Z"))))
                .isInstanceOf(InvalidGameSessionDateException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void strangerCannotScheduleSession() {
        UUID gameId = UUID.randomUUID();
        Game game = game(UUID.randomUUID(), null);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().schedule(
                UUID.randomUUID(), gameId, UUID.randomUUID(),
                new ScheduleGameSessionRequest(Instant.parse("2099-03-01T18:00:00Z"))))
                .isInstanceOf(GameSessionAccessDeniedException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void masterStartsScheduledSession() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession();
        session.setStatus(GameSessionStatus.SCHEDULED);
        Game game = game(masterId, null);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any(GameSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GameSessionResponse response = service().start(masterId, gameId, sessionId);

        assertThat(response.status()).isEqualTo(GameSessionStatus.IN_PROGRESS);
    }

    @Test
    void startedSessionIsNotStartedAgain() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession();
        session.setStatus(GameSessionStatus.IN_PROGRESS);
        Game game = game(masterId, null);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service().start(masterId, gameId, sessionId))
                .isInstanceOf(InvalidGameSessionStateException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void masterCompletesSessionFromAnyLiveState() {
        for (GameSessionStatus status : List.of(
                GameSessionStatus.SCHEDULED, GameSessionStatus.IN_PROGRESS)) {
            UUID masterId = UUID.randomUUID();
            UUID gameId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            GameSession session = new GameSession();
            session.setStatus(status);
            Game game = game(masterId, null);
            when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
            when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                    .thenReturn(Optional.of(session));
            when(sessionRepository.save(any(GameSession.class)))
                    .thenAnswer(invocation -> invocation.getArgument(0));

            // Отменённый набор закрывается тем же действием, что и сыгранная сессия.
            GameSessionResponse response = service().complete(masterId, gameId, sessionId);

            assertThat(response.status()).isEqualTo(GameSessionStatus.COMPLETED);
        }
    }

    @Test
    void masterCancelsSessionThatDidNotHappen() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession();
        session.setStatus(GameSessionStatus.SCHEDULED);
        Game game = game(masterId, null);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));
        when(sessionRepository.save(any(GameSession.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GameSessionResponse response = service().cancel(masterId, gameId, sessionId);

        // Отмена — отдельный исход: по завершённым видно, что было сыграно.
        assertThat(response.status()).isEqualTo(GameSessionStatus.CANCELLED);
    }

    @Test
    void closedSessionIsNotClosedAgain() {
        for (GameSessionStatus status : List.of(
                GameSessionStatus.COMPLETED, GameSessionStatus.CANCELLED)) {
            UUID masterId = UUID.randomUUID();
            UUID gameId = UUID.randomUUID();
            UUID sessionId = UUID.randomUUID();
            GameSession session = new GameSession();
            session.setStatus(status);
            Game game = game(masterId, null);
            when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
            when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                    .thenReturn(Optional.of(session));

            assertThatThrownBy(() -> service().cancel(masterId, gameId, sessionId))
                    .isInstanceOf(InvalidGameSessionStateException.class);
            assertThatThrownBy(() -> service().complete(masterId, gameId, sessionId))
                    .isInstanceOf(InvalidGameSessionStateException.class);
        }

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void completedSessionIsNotCompletedAgain() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        GameSession session = new GameSession();
        session.setStatus(GameSessionStatus.COMPLETED);
        Game game = game(masterId, null);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));

        assertThatThrownBy(() -> service().complete(masterId, gameId, sessionId))
                .isInstanceOf(InvalidGameSessionStateException.class);

        verify(sessionRepository, never()).save(any());
    }

    @Test
    void strangerChangesNoSessionState() {
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = game(UUID.randomUUID(), null);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().start(UUID.randomUUID(), gameId, sessionId))
                .isInstanceOf(GameSessionAccessDeniedException.class);
        assertThatThrownBy(() -> service().complete(UUID.randomUUID(), gameId, sessionId))
                .isInstanceOf(GameSessionAccessDeniedException.class);

        verify(sessionRepository, never()).save(any());
    }

    private GameSessionService service() {
        return new GameSessionService(
                gameRepository, sessionRepository, registrationRepository,
                gameRegistrationRepository, mapper, notificationService, chatService);
    }

    /** Заявка на сессию бесплатной игры с произвольной датой. */
    private CreateGameSessionRequest freeRequest(Instant startsAt) {
        return new CreateGameSessionRequest(
                "Первая глава", startsAt, 240, null, null, null);
    }

    private Game game(UUID masterId, GameCostType costType) {
        Game game = mock(Game.class);
        when(game.getMasterId()).thenReturn(masterId);
        if (costType != null) {
            when(game.getCostType()).thenReturn(costType);
        }
        return game;
    }

    private CreateGameSessionRequest request() {
        return new CreateGameSessionRequest(
                "Первая глава",
                Instant.parse("2099-01-10T18:00:00Z"),
                240,
                new BigDecimal("15.00"),
                "EUR",
                SessionPaymentType.PREPAYMENT);
    }
}
