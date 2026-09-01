package club.ttg.findgame.registration;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameVisibility;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import club.ttg.findgame.registration.api.CreateGameRegistrationRequest;
import club.ttg.findgame.registration.api.GameRegistrationResponse;
import club.ttg.findgame.registration.api.ReviewGameRegistrationRequest;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.session.GameSessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Заявка подаётся в игру, а не в сессию: принятый игрок входит в состав и
 * попадает во все запланированные встречи.
 */
@ExtendWith(MockitoExtension.class)
class GameRegistrationServiceTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSessionRepository sessionRepository;

    @Mock
    private GameRegistrationRepository registrationRepository;

    @Mock
    private SessionRegistrationRepository participantRepository;

    @Mock
    private NotificationService notificationService;

    @Test
    void playerAppliesToGameOnce() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = publicGame(masterId, gameId);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.findByGameIdAndPlayerId(gameId, playerId))
                .thenReturn(Optional.empty());
        when(registrationRepository.saveAndFlush(any(GameRegistration.class)))
                .thenAnswer(invocation -> {
                    GameRegistration saved = invocation.getArgument(0);
                    saved.prePersist();
                    return saved;
                });

        GameRegistrationResponse response = service().register(
                playerId, gameId, null,
                new CreateGameRegistrationRequest(null, "Тассельхоф Непоседа"));

        assertThat(response.status()).isEqualTo(RegistrationStatus.PENDING);
        assertThat(response.characterName()).isEqualTo("Тассельхоф Непоседа");
        verify(notificationService).notifyUser(
                eq(masterId), eq(playerId), eq(NotificationType.REGISTRATION_SUBMITTED),
                eq(gameId), any(), eq(null), eq(null));
    }

    @Test
    void secondApplicationToSameGameIsRejected() {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = publicGame(UUID.randomUUID(), gameId);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.findByGameIdAndPlayerId(gameId, playerId))
                .thenReturn(Optional.of(registration(gameId, playerId, RegistrationStatus.PENDING)));

        assertThatThrownBy(() -> service().register(
                playerId, gameId, null, new CreateGameRegistrationRequest(null, null)))
                .isInstanceOf(InvalidSessionRegistrationException.class);

        verify(registrationRepository, never()).saveAndFlush(any());
    }

    @Test
    void masterDoesNotApplyToOwnGame() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = publicGame(masterId, gameId);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().register(
                masterId, gameId, null, new CreateGameRegistrationRequest(null, null)))
                .isInstanceOf(InvalidSessionRegistrationException.class);
    }

    @Test
    void approvedPlayerJoinsEveryScheduledSession() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID scheduledId = UUID.randomUUID();
        Game game = publicGame(masterId, gameId);
        when(game.getMaxPlayers()).thenReturn(5);
        GameRegistration registration = registration(gameId, playerId, RegistrationStatus.PENDING);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.findByIdAndGameId(registration.getId(), gameId))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.countByGameIdAndStatus(gameId, RegistrationStatus.APPROVED))
                .thenReturn(0L);
        List<GameSession> sessions = List.of(
                session(scheduledId, GameSessionStatus.SCHEDULED),
                session(UUID.randomUUID(), GameSessionStatus.COMPLETED));
        when(sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId)).thenReturn(sessions);
        when(participantRepository.existsBySessionIdAndPlayerId(scheduledId, playerId))
                .thenReturn(false);
        when(registrationRepository.save(any(GameRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().review(masterId, gameId, registration.getId(),
                new ReviewGameRegistrationRequest(RegistrationDecision.APPROVE, null));

        // Заявка подаётся в игру, поэтому принятый сразу попадает в её
        // запланированные встречи. Сыгранные состав задним числом не меняют.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<SessionRegistration>> captor =
                ArgumentCaptor.forClass(Iterable.class);
        verify(participantRepository).saveAll(captor.capture());
        assertThat(captor.getValue()).singleElement().satisfies(participation -> {
            assertThat(participation.getSessionId()).isEqualTo(scheduledId);
            assertThat(participation.getPlayerId()).isEqualTo(playerId);
            assertThat(participation.getAttendanceStatus())
                    .isEqualTo(SessionAttendanceStatus.NOT_ATTENDING);
        });
    }

    @Test
    void gameDoesNotTakeMorePlayersThanSeats() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = publicGame(masterId, gameId);
        when(game.getMaxPlayers()).thenReturn(3);
        GameRegistration registration =
                registration(gameId, UUID.randomUUID(), RegistrationStatus.PENDING);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.findByIdAndGameId(registration.getId(), gameId))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.countByGameIdAndStatus(gameId, RegistrationStatus.APPROVED))
                .thenReturn(3L);

        assertThatThrownBy(() -> service().review(masterId, gameId, registration.getId(),
                new ReviewGameRegistrationRequest(RegistrationDecision.APPROVE, null)))
                .isInstanceOf(InvalidSessionRegistrationException.class);

        verify(participantRepository, never()).saveAll(any());
    }

    @Test
    void excludedPlayerLeavesEveryOpenSession() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID scheduledId = UUID.randomUUID();
        UUID inProgressId = UUID.randomUUID();
        Game game = publicGame(masterId, gameId);
        GameRegistration registration = registration(gameId, playerId, RegistrationStatus.APPROVED);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.findByIdAndGameId(registration.getId(), gameId))
                .thenReturn(Optional.of(registration));
        List<GameSession> sessions = List.of(
                session(scheduledId, GameSessionStatus.SCHEDULED),
                session(inProgressId, GameSessionStatus.IN_PROGRESS),
                session(UUID.randomUUID(), GameSessionStatus.COMPLETED),
                session(UUID.randomUUID(), GameSessionStatus.CANCELLED));
        when(sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId)).thenReturn(sessions);
        when(registrationRepository.save(any(GameRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().review(masterId, gameId, registration.getId(),
                new ReviewGameRegistrationRequest(RegistrationDecision.REJECT, null));

        // Исключение убирает игрока из незакрытых встреч; в сыгранных и
        // отменённых его участие остаётся историей.
        verify(participantRepository).deleteBySessionIdInAndPlayerId(
                List.of(scheduledId, inProgressId), playerId);
    }

    @Test
    void rejectionKeepsMasterReasonAndApprovalClearsIt() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = publicGame(masterId, gameId);
        GameRegistration registration = registration(gameId, playerId, RegistrationStatus.PENDING);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(game.getMaxPlayers()).thenReturn(5);
        when(registrationRepository.findByIdAndGameId(registration.getId(), gameId))
                .thenReturn(Optional.of(registration));
        when(sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId)).thenReturn(List.of());
        when(registrationRepository.save(any(GameRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GameRegistrationService service = service();

        GameRegistrationResponse rejected = service.review(masterId, gameId, registration.getId(),
                new ReviewGameRegistrationRequest(RegistrationDecision.REJECT, "  Состав собран  "));

        // Причина обрезается по краям: игроку показывают текст, а не отступы.
        assertThat(rejected.rejectionReason()).isEqualTo("Состав собран");

        when(registrationRepository.countByGameIdAndStatus(gameId, RegistrationStatus.APPROVED))
                .thenReturn(0L);

        GameRegistrationResponse approved = service.review(masterId, gameId, registration.getId(),
                new ReviewGameRegistrationRequest(RegistrationDecision.APPROVE, null));

        // Прежний отказ снят — причина к принятой заявке уже не относится.
        assertThat(approved.rejectionReason()).isNull();
    }

    @Test
    void blankRejectionReasonIsStoredAsNoReason() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = publicGame(masterId, gameId);
        GameRegistration registration =
                registration(gameId, UUID.randomUUID(), RegistrationStatus.PENDING);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.findByIdAndGameId(registration.getId(), gameId))
                .thenReturn(Optional.of(registration));
        when(sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId)).thenReturn(List.of());
        when(registrationRepository.save(any(GameRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        GameRegistrationResponse rejected = service().review(masterId, gameId, registration.getId(),
                new ReviewGameRegistrationRequest(RegistrationDecision.REJECT, "   "));

        assertThat(rejected.rejectionReason()).isNull();
    }

    @Test
    void playerWithdrawsOwnPendingApplication() {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        GameRegistration registration = registration(gameId, playerId, RegistrationStatus.PENDING);
        when(registrationRepository.findByGameIdAndPlayerId(gameId, playerId))
                .thenReturn(Optional.of(registration));

        service().withdraw(playerId, gameId);

        // Отозванная удаляется, а не помечается: на отклонённую заявку игрок
        // повторно подать уже не сможет, а на отозванную — да.
        verify(registrationRepository).delete(registration);
    }

    @Test
    void approvedApplicationIsNotWithdrawnByPlayer() {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        when(registrationRepository.findByGameIdAndPlayerId(gameId, playerId))
                .thenReturn(Optional.of(registration(gameId, playerId, RegistrationStatus.APPROVED)));

        // Место согласовано: тихий уход из состава подвёл бы группу.
        assertThatThrownBy(() -> service().withdraw(playerId, gameId))
                .isInstanceOf(InvalidSessionRegistrationException.class);

        verify(registrationRepository, never()).delete(any());
    }

    @Test
    void strangerDoesNotReviewApplications() {
        UUID gameId = UUID.randomUUID();
        Game game = publicGame(UUID.randomUUID(), gameId);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().review(
                UUID.randomUUID(), gameId, UUID.randomUUID(),
                new ReviewGameRegistrationRequest(RegistrationDecision.APPROVE, null)))
                .isInstanceOf(SessionRegistrationAccessDeniedException.class);

        verify(registrationRepository, never()).save(any());
    }

    @Test
    void privateGameIsInvisibleWithoutInviteCode() {
        UUID gameId = UUID.randomUUID();
        Game game = mock(Game.class);
        lenient().when(game.getId()).thenReturn(gameId);
        when(game.getVisibility()).thenReturn(GameVisibility.PRIVATE);
        lenient().when(game.getInviteCode()).thenReturn(UUID.randomUUID());
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().register(
                UUID.randomUUID(), gameId, null,
                new CreateGameRegistrationRequest(null, null)))
                .isInstanceOf(club.ttg.findgame.game.GameNotFoundException.class);
    }

    private GameRegistrationService service() {
        return new GameRegistrationService(
                gameRepository, sessionRepository, registrationRepository,
                participantRepository, notificationService);
    }

    /** Публичная игра с заданным мастером. */
    private static Game publicGame(UUID masterId, UUID gameId) {
        Game game = mock(Game.class);
        lenient().when(game.getId()).thenReturn(gameId);
        lenient().when(game.getMasterId()).thenReturn(masterId);
        lenient().when(game.getTitle()).thenReturn("Проклятие Страда");
        lenient().when(game.getVisibility()).thenReturn(GameVisibility.PUBLIC);

        return game;
    }

    /** Заявка игрока с заданным состоянием. */
    private static GameRegistration registration(
            UUID gameId,
            UUID playerId,
            RegistrationStatus status
    ) {
        GameRegistration registration = new GameRegistration();
        registration.setId(UUID.randomUUID());
        registration.setGameId(gameId);
        registration.setPlayerId(playerId);
        registration.setStatus(status);

        return registration;
    }

    /** Сессия игры с заданным состоянием. */
    private static GameSession session(UUID sessionId, GameSessionStatus status) {
        GameSession session = mock(GameSession.class);
        lenient().when(session.getId()).thenReturn(sessionId);
        lenient().when(session.getStatus()).thenReturn(status);

        return session;
    }
}
