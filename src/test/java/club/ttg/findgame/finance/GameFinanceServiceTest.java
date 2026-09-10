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
import club.ttg.findgame.session.GameSessionStatus;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @Test
    void topUpClosesFinalizedDebtWithoutChargingTwice() {
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Game game = paidGame(gameId, masterId);
        GameSession session = mock(GameSession.class);
        SessionBill debt = bill(gameId, sessionId, playerId, "500.00");
        debt.setBooked(new BigDecimal("500.00"));
        debt.setFinalized(true);

        when(session.getId()).thenReturn(sessionId);
        when(sessions.findAllByGameIdOrderByStartsAtAsc(gameId)).thenReturn(List.of(session));
        when(bills.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, playerId)).thenReturn(List.of(debt));
        when(bills.save(any(SessionBill.class))).thenAnswer(invocation -> invocation.getArgument(0));
        // Долг уже списан с баланса при завершении встречи, депозит вернул его к нулю.
        when(entries.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, playerId)).thenReturn(List.of(
                entry(gameId, playerId, "-500.00", "SESSION_CHARGE", masterId),
                entry(gameId, playerId, "500.00", "TOP_UP", masterId)));

        service.addEntry(masterId, gameId, playerId, topUp("500.00"));

        assertEquals(0, debt.remaining().signum());
        verify(entries, times(1)).save(any(FinanceEntry.class));
    }

    @Test
    void topUpReservesUpcomingSession() {
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID playerId = UUID.randomUUID();
        UUID masterId = UUID.randomUUID();
        Game game = paidGame(gameId, masterId);
        GameSession session = mock(GameSession.class);

        when(session.getId()).thenReturn(sessionId);
        when(session.getGameId()).thenReturn(gameId);
        when(session.getStatus()).thenReturn(GameSessionStatus.SCHEDULED);
        when(session.getPriceAmount()).thenReturn(new BigDecimal("300.00"));
        when(session.getPriceCurrency()).thenReturn("RUB");
        when(sessions.findAllByGameIdOrderByStartsAtAsc(gameId)).thenReturn(List.of(session));
        when(bills.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, playerId)).thenReturn(List.of());
        when(bills.save(any(SessionBill.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(participants.findBySessionIdAndPlayerId(sessionId, playerId))
                .thenReturn(Optional.of(SessionRegistration.of(sessionId, playerId)));
        when(entries.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, playerId))
                .thenReturn(List.of(entry(gameId, playerId, "500.00", "TOP_UP", masterId)));

        service.addEntry(masterId, gameId, playerId, topUp("500.00"));

        ArgumentCaptor<FinanceEntry> saved = ArgumentCaptor.forClass(FinanceEntry.class);
        verify(entries, times(2)).save(saved.capture());
        FinanceEntry reservation = saved.getAllValues().get(1);
        assertEquals("RESERVATION", reservation.getKind());
        assertEquals(0, new BigDecimal("-300.00").compareTo(reservation.getAmount()));
    }

    /** Платная игра, бухгалтерию которой ведёт мастер запроса. */
    private Game paidGame(UUID gameId, UUID masterId) {
        Game game = mock(Game.class);
        when(game.getCostType()).thenReturn(GameCostType.PAID);
        when(game.getMasterId()).thenReturn(masterId);
        when(games.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(registrations.existsByGameIdAndPlayerIdAndStatus(eq(gameId), any(), any())).thenReturn(true);
        return game;
    }

    /** Запрос на пополнение счёта в рублях. */
    private static CreateFinanceEntryRequest topUp(String amount) {
        return new CreateFinanceEntryRequest(UUID.randomUUID(), new BigDecimal(amount), "RUB", "TOP_UP", null);
    }

    /** Запись журнала для подсчёта баланса. */
    private static FinanceEntry entry(UUID gameId, UUID playerId, String amount, String kind, UUID actorId) {
        return new FinanceEntry(UUID.randomUUID(), gameId, playerId, null, new BigDecimal(amount), "RUB", kind, null, actorId);
    }

    /** Расчёт за встречу без начислений и оплат. */
    private static SessionBill bill(UUID gameId, UUID sessionId, UUID playerId, String amount) {
        SessionBill bill = new SessionBill();
        bill.setGameId(gameId);
        bill.setSessionId(sessionId);
        bill.setPlayerId(playerId);
        bill.setAmount(new BigDecimal(amount));
        bill.setCurrency("RUB");
        return bill;
    }
}
