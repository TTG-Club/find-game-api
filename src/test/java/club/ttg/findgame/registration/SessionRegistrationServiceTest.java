package club.ttg.findgame.registration;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.registration.api.UpdateAttendanceRequest;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.session.GameSessionStatus;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class SessionRegistrationServiceTest {
    private final GameRepository games = mock(GameRepository.class);
    private final GameSessionRepository sessions = mock(GameSessionRepository.class);
    private final SessionRegistrationRepository participants = mock(SessionRegistrationRepository.class);
    private final SessionRegistrationService service = new SessionRegistrationService(games, sessions, participants,
            mock(club.ttg.findgame.finance.GameFinanceService.class));

    @Test
    void playerCanSetAndResetOwnAttendanceForShownSession() {
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        Game game = mock(Game.class);
        GameSession session = mock(GameSession.class);
        when(session.getStatus()).thenReturn(GameSessionStatus.SCHEDULED);
        when(games.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessions.findByIdAndGameId(sessionId, gameId)).thenReturn(Optional.of(session));
        SessionRegistration participation = SessionRegistration.of(sessionId, playerId);
        when(participants.findBySessionIdAndPlayerId(sessionId, playerId)).thenReturn(Optional.of(participation));
        when(participants.save(participation)).thenReturn(participation);

        assertThat(participation.getAttendanceStatus()).isEqualTo(SessionAttendanceStatus.UNMARKED);
        for (SessionAttendanceStatus status : SessionAttendanceStatus.values()) {
            var result = service.updateAttendance(playerId, gameId, sessionId, new UpdateAttendanceRequest(status));
            assertThat(result.attendanceStatus()).isEqualTo(status);
            assertThat(result.sessionId()).isEqualTo(sessionId);
            assertThat(result.playerId()).isEqualTo(playerId);
        }
    }

    @Test
    void closedSessionCannotBeChanged() {
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = mock(Game.class);
        GameSession session = mock(GameSession.class);
        when(session.getStatus()).thenReturn(GameSessionStatus.COMPLETED);
        when(games.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessions.findByIdAndGameId(sessionId, gameId)).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> service.updateAttendance(UUID.randomUUID(), gameId, sessionId,
                new UpdateAttendanceRequest(SessionAttendanceStatus.ATTENDING)))
                .isInstanceOf(InvalidSessionRegistrationException.class);
        verify(participants, never()).save(any());
    }

    @Test
    void strangerCannotMarkAttendance() {
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = mock(Game.class);
        GameSession session = mock(GameSession.class);
        when(session.getStatus()).thenReturn(GameSessionStatus.SCHEDULED);
        when(games.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessions.findByIdAndGameId(sessionId, gameId)).thenReturn(Optional.of(session));
        assertThatThrownBy(() -> service.updateAttendance(UUID.randomUUID(), gameId, sessionId,
                new UpdateAttendanceRequest(SessionAttendanceStatus.ATTENDING)))
                .isInstanceOf(InvalidSessionRegistrationException.class);
        verify(participants, never()).save(any());
    }
}
