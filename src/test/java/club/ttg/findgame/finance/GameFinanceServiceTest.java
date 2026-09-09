package club.ttg.findgame.finance;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.SessionAttendanceStatus;
import club.ttg.findgame.registration.SessionRegistration;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class GameFinanceServiceTest {
    private final GameRepository games = mock(GameRepository.class);
    private final GameSessionRepository sessions = mock(GameSessionRepository.class);
    private final GameRegistrationRepository registrations = mock(GameRegistrationRepository.class);
    private final SessionRegistrationRepository participants = mock(SessionRegistrationRepository.class);
    private final FinanceEntryRepository entries = mock(FinanceEntryRepository.class);
    private final SessionBillRepository bills = mock(SessionBillRepository.class);
    private final GameFinanceService service = new GameFinanceService(
            games, sessions, registrations, participants, entries, bills);

    @Test
    void completionChargesOnlyAttendingPlayers() {
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = mock(Game.class);
        GameSession session = mock(GameSession.class);
        SessionRegistration attending = SessionRegistration.of(sessionId, UUID.randomUUID());
        attending.setAttendanceStatus(SessionAttendanceStatus.ATTENDING);
        SessionRegistration unmarked = SessionRegistration.of(sessionId, UUID.randomUUID());

        when(game.getId()).thenReturn(gameId);
        when(game.getCostType()).thenReturn(GameCostType.PAID);
        when(session.getId()).thenReturn(sessionId);
        when(session.getGameId()).thenReturn(gameId);
        when(session.getPriceAmount()).thenReturn(new BigDecimal("500.00"));
        when(session.getPriceCurrency()).thenReturn("RUB");
        when(participants.findAllBySessionIdOrderByCreatedAtAsc(sessionId))
                .thenReturn(List.of(attending, unmarked));
        when(bills.findBySessionIdAndPlayerId(any(), any())).thenReturn(java.util.Optional.empty());
        when(bills.save(any(SessionBill.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(entries.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(any(), any())).thenReturn(List.of());

        service.finish(game, session, false, UUID.randomUUID());

        verify(entries, times(1)).save(any(FinanceEntry.class));
        verify(bills, times(4)).save(any(SessionBill.class));
    }
}
