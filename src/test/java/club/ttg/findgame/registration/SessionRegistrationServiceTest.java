package club.ttg.findgame.registration;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameVisibility;
import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.registration.api.CreateSessionRegistrationRequest;
import club.ttg.findgame.registration.api.ReviewSessionRegistrationRequest;
import club.ttg.findgame.registration.api.SessionRegistrationResponse;
import club.ttg.findgame.registration.api.UpdateAttendanceRequest;
import club.ttg.findgame.registration.api.UpdatePaymentStatusRequest;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.session.GameSessionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRegistrationServiceTest {

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameSessionRepository sessionRepository;

    @Mock
    private SessionRegistrationRepository registrationRepository;

    private final SessionRegistrationMapper mapper = Mappers.getMapper(SessionRegistrationMapper.class);

    @Test
    void playerRegistersWithCharacterSheetAsPending() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = publicGame(gameId, masterId);
        GameSession session = scheduledSession();
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));
        when(registrationRepository.saveAndFlush(any(SessionRegistration.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        SessionRegistrationResponse response = service().register(
                playerId, gameId, sessionId, null,
                new CreateSessionRegistrationRequest("https://ttg.club/characters/strahd-hunter"));

        assertThat(response.playerId()).isEqualTo(playerId);
        assertThat(response.sessionId()).isEqualTo(sessionId);
        assertThat(response.characterSheetUrl()).isEqualTo("https://ttg.club/characters/strahd-hunter");
        assertThat(response.status()).isEqualTo(SessionRegistrationStatus.PENDING);
    }

    @Test
    void duplicateRegistrationIsRejected() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = publicGame(gameId, masterId);
        GameSession session = scheduledSession();
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));
        when(registrationRepository.existsBySessionIdAndPlayerId(sessionId, playerId)).thenReturn(true);

        assertThatThrownBy(() -> service().register(
                playerId, gameId, sessionId, null, new CreateSessionRegistrationRequest(null)))
                .isInstanceOf(InvalidSessionRegistrationException.class);
        verify(registrationRepository, never()).saveAndFlush(any());
    }

    @Test
    void concurrentDuplicateRegistrationIsConvertedToDomainError() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = publicGame(gameId, masterId);
        GameSession session = scheduledSession();
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));
        when(registrationRepository.saveAndFlush(any(SessionRegistration.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        assertThatThrownBy(() -> service().register(
                playerId, gameId, sessionId, null, new CreateSessionRegistrationRequest(null)))
                .isInstanceOf(InvalidSessionRegistrationException.class)
                .hasMessageContaining("уже подал заявку");
    }

    @Test
    void onlyMasterCanReadRegistrations() {
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = publicGame(gameId, UUID.randomUUID());
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().findAllForMaster(UUID.randomUUID(), gameId, sessionId))
                .isInstanceOf(SessionRegistrationAccessDeniedException.class);
        verify(sessionRepository, never()).findByIdAndGameId(any(), any());
    }

    @Test
    void masterApprovesRegistration() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        Game game = mock(Game.class);
        when(game.getMasterId()).thenReturn(masterId);
        when(game.getMaxPlayers()).thenReturn(5);
        SessionRegistration registration = registration(registrationId, sessionId);
        GameSession session = scheduledSession();
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));
        when(registrationRepository.findByIdAndSessionId(registrationId, sessionId))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.countBySessionIdAndStatus(sessionId, SessionRegistrationStatus.APPROVED))
                .thenReturn(4L);
        when(registrationRepository.save(registration)).thenReturn(registration);

        SessionRegistrationResponse response = service().review(
                masterId, gameId, sessionId, registrationId,
                new ReviewSessionRegistrationRequest(RegistrationDecision.APPROVE));

        assertThat(response.status()).isEqualTo(SessionRegistrationStatus.APPROVED);
        assertThat(response.attendanceStatus()).isEqualTo(SessionAttendanceStatus.NOT_ATTENDING);
    }

    @Test
    void masterCannotApproveBeyondMaximumPlayers() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        Game game = mock(Game.class);
        when(game.getMasterId()).thenReturn(masterId);
        when(game.getMaxPlayers()).thenReturn(5);
        GameSession session = scheduledSession();
        SessionRegistration registration = registration(registrationId, sessionId);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));
        when(registrationRepository.findByIdAndSessionId(registrationId, sessionId))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.countBySessionIdAndStatus(sessionId, SessionRegistrationStatus.APPROVED))
                .thenReturn(5L);

        assertThatThrownBy(() -> service().review(
                masterId, gameId, sessionId, registrationId,
                new ReviewSessionRegistrationRequest(RegistrationDecision.APPROVE)))
                .isInstanceOf(InvalidSessionRegistrationException.class);
        verify(registrationRepository, never()).save(any());
    }

    @Test
    void rejectingApprovedRegistrationClearsAttendanceAndPayment() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        Game game = mock(Game.class);
        GameSession session = scheduledSession();
        SessionRegistration registration = registration(registrationId, sessionId);
        registration.setStatus(SessionRegistrationStatus.APPROVED);
        registration.setAttendanceStatus(SessionAttendanceStatus.ATTENDING);
        registration.setPaidAt(java.time.Instant.parse("2026-08-20T12:00:00Z"));
        when(game.getMasterId()).thenReturn(masterId);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(session));
        when(registrationRepository.findByIdAndSessionId(registrationId, sessionId))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.save(registration)).thenReturn(registration);

        SessionRegistrationResponse response = service().review(
                masterId,
                gameId,
                sessionId,
                registrationId,
                new ReviewSessionRegistrationRequest(RegistrationDecision.REJECT));

        assertThat(response.status()).isEqualTo(SessionRegistrationStatus.REJECTED);
        assertThat(response.attendanceStatus()).isNull();
        assertThat(response.paid()).isFalse();
        assertThat(response.paidAt()).isNull();
    }

    @Test
    void approvedPlayerChangesOwnAttendance() {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = mock(Game.class);
        GameSession session = scheduledSession();
        SessionRegistration registration = registration(UUID.randomUUID(), sessionId);
        registration.setPlayerId(playerId);
        registration.setStatus(SessionRegistrationStatus.APPROVED);
        registration.setAttendanceStatus(SessionAttendanceStatus.NOT_ATTENDING);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId)).thenReturn(Optional.of(session));
        when(registrationRepository.findBySessionIdAndPlayerId(sessionId, playerId))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.save(registration)).thenReturn(registration);

        SessionRegistrationResponse response = service().updateAttendance(
                playerId,
                gameId,
                sessionId,
                new UpdateAttendanceRequest(SessionAttendanceStatus.ATTENDING));

        assertThat(response.attendanceStatus()).isEqualTo(SessionAttendanceStatus.ATTENDING);
    }

    @Test
    void pendingPlayerCannotChangeAttendance() {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = mock(Game.class);
        GameSession session = scheduledSession();
        SessionRegistration registration = registration(UUID.randomUUID(), sessionId);
        registration.setPlayerId(playerId);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId)).thenReturn(Optional.of(session));
        when(registrationRepository.findBySessionIdAndPlayerId(sessionId, playerId))
                .thenReturn(Optional.of(registration));

        assertThatThrownBy(() -> service().updateAttendance(
                playerId,
                gameId,
                sessionId,
                new UpdateAttendanceRequest(SessionAttendanceStatus.ATTENDING)))
                .isInstanceOf(InvalidSessionRegistrationException.class);
        verify(registrationRepository, never()).save(any());
    }

    @Test
    void masterMarksApprovedPlayerAsPaidRegardlessOfSessionStatus() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID registrationId = UUID.randomUUID();
        Game game = mock(Game.class);
        GameSession completedSession = mock(GameSession.class);
        SessionRegistration registration = registration(registrationId, sessionId);
        registration.setStatus(SessionRegistrationStatus.APPROVED);
        when(game.getMasterId()).thenReturn(masterId);
        when(game.getCostType()).thenReturn(GameCostType.PAID);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId))
                .thenReturn(Optional.of(completedSession));
        when(registrationRepository.findByIdAndSessionId(registrationId, sessionId))
                .thenReturn(Optional.of(registration));
        when(registrationRepository.save(registration)).thenReturn(registration);

        SessionRegistrationResponse response = service().updatePaymentStatus(
                masterId, gameId, sessionId, registrationId, new UpdatePaymentStatusRequest(true));

        assertThat(response.paid()).isTrue();
        assertThat(response.paidAt()).isNotNull();
        verify(completedSession, never()).getStatus();

        SessionRegistrationResponse repeated = service().updatePaymentStatus(
                masterId, gameId, sessionId, registrationId, new UpdatePaymentStatusRequest(true));
        assertThat(repeated.paidAt()).isEqualTo(response.paidAt());

        SessionRegistrationResponse cleared = service().updatePaymentStatus(
                masterId, gameId, sessionId, registrationId, new UpdatePaymentStatusRequest(false));
        assertThat(cleared.paid()).isFalse();
        assertThat(cleared.paidAt()).isNull();
    }

    @Test
    void paymentCannotBeMarkedForFreeGame() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = mock(Game.class);
        when(game.getMasterId()).thenReturn(masterId);
        when(game.getCostType()).thenReturn(GameCostType.FREE);
        when(gameRepository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().updatePaymentStatus(
                masterId,
                gameId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new UpdatePaymentStatusRequest(true)))
                .isInstanceOf(InvalidSessionRegistrationException.class);
        verify(registrationRepository, never()).save(any());
    }

    @Test
    void playerReadsOwnPendingRegistration() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        SessionRegistration own = registration(UUID.randomUUID(), sessionId);
        own.setPlayerId(playerId);
        Game game = publicGame(gameId, masterId);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        GameSession session = scheduledSession();
        when(sessionRepository.findByIdAndGameId(sessionId, gameId)).thenReturn(Optional.of(session));
        when(registrationRepository.findBySessionIdAndPlayerId(sessionId, playerId))
                .thenReturn(Optional.of(own));

        SessionRegistrationResponse response = service().findOwn(playerId, gameId, sessionId, null);

        assertThat(response.playerId()).isEqualTo(playerId);
        assertThat(response.status()).isEqualTo(SessionRegistrationStatus.PENDING);
    }

    @Test
    void missingOwnRegistrationIsNotFound() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = publicGame(gameId, masterId);
        GameSession session = scheduledSession();
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findByIdAndGameId(sessionId, gameId)).thenReturn(Optional.of(session));
        when(registrationRepository.findBySessionIdAndPlayerId(sessionId, playerId))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().findOwn(playerId, gameId, sessionId, null))
                .isInstanceOf(SessionRegistrationNotFoundException.class);
    }

    @Test
    void privateGameHidesOwnRegistrationWithoutInviteCode() {
        UUID masterId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = mock(Game.class);
        when(game.getVisibility()).thenReturn(GameVisibility.PRIVATE);
        when(game.getMasterId()).thenReturn(masterId);
        lenient().when(game.getId()).thenReturn(gameId);
        when(gameRepository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().findOwn(playerId, gameId, sessionId, null))
                .isInstanceOf(GameNotFoundException.class);
        verify(registrationRepository, never()).findBySessionIdAndPlayerId(any(), any());
    }

    private SessionRegistrationService service() {
        return new SessionRegistrationService(
                gameRepository, sessionRepository, registrationRepository, mapper);
    }

    private Game publicGame(UUID gameId, UUID masterId) {
        Game game = mock(Game.class);
        when(game.getMasterId()).thenReturn(masterId);
        lenient().when(game.getVisibility()).thenReturn(GameVisibility.PUBLIC);
        return game;
    }

    private GameSession scheduledSession() {
        GameSession session = mock(GameSession.class);
        lenient().when(session.getStatus()).thenReturn(GameSessionStatus.SCHEDULED);
        return session;
    }

    private SessionRegistration registration(UUID registrationId, UUID sessionId) {
        SessionRegistration registration = new SessionRegistration();
        registration.setId(registrationId);
        registration.setSessionId(sessionId);
        registration.setPlayerId(UUID.randomUUID());
        registration.setStatus(SessionRegistrationStatus.PENDING);
        return registration;
    }
}
