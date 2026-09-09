package club.ttg.findgame.finance;

import club.ttg.findgame.game.*;
import club.ttg.findgame.registration.*;
import club.ttg.findgame.session.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/** Учёт авансов и долгов. Все изменения сериализуются блокировкой игры. */
@Service
@Transactional
public class GameFinanceService {
    private final GameRepository games;
    private final GameSessionRepository sessions;
    private final GameRegistrationRepository registrations;
    private final SessionRegistrationRepository participants;
    private final FinanceEntryRepository entries;
    private final SessionBillRepository bills;

    public GameFinanceService(GameRepository games, GameSessionRepository sessions,
                              GameRegistrationRepository registrations, SessionRegistrationRepository participants,
                              FinanceEntryRepository entries, SessionBillRepository bills) {
        this.games = games;
        this.sessions = sessions;
        this.registrations = registrations;
        this.participants = participants;
        this.entries = entries;
        this.bills = bills;
    }

    /** Мастер видит все счета, игрок — исключительно свой, включая историю после выхода. */
    @Transactional(readOnly = true)
    public FinanceResponse read(UUID actorId, UUID gameId, boolean ownOnly) {
        Game game = games.findByIdAndDeletedAtIsNull(gameId).orElseThrow(() -> new GameNotFoundException(gameId));
        requirePaid(game);
        if (!ownOnly) requireMaster(game, actorId);
        List<FinanceEntry> history = ownOnly
                ? entries.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, actorId)
                : entries.findAllByGameIdOrderByCreatedAtAsc(gameId);
        List<SessionBill> knownBills = ownOnly
                ? bills.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, actorId)
                : bills.findAllByGameIdOrderByCreatedAtAsc(gameId);
        List<GameRegistration> roster = registrations.findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED);
        Set<UUID> playerIds = new LinkedHashSet<>();
        roster.forEach(registration -> playerIds.add(registration.getPlayerId()));
        history.forEach(entry -> playerIds.add(entry.getPlayerId()));
        knownBills.forEach(bill -> playerIds.add(bill.getPlayerId()));
        if (ownOnly) {
            if (!playerIds.contains(actorId)) throw new SessionRegistrationAccessDeniedException("Счёт недоступен");
            playerIds.clear();
            playerIds.add(actorId);
        }
        List<GameSession> gameSessions = sessions.findAllByGameIdOrderByStartsAtAsc(gameId);
        List<SessionRegistration> attendance = participants.findAllBySessionIdIn(
                gameSessions.stream().map(GameSession::getId).toList());
        List<FinanceResponse.Account> accounts = new ArrayList<>();
        for (UUID playerId : playerIds) {
            List<FinanceEntry> playerHistory = history.stream().filter(entry -> entry.getPlayerId().equals(playerId)).toList();
            Map<String, BigDecimal> balances = new TreeMap<>();
            gameSessions.stream().map(GameSession::getPriceCurrency).filter(Objects::nonNull)
                    .forEach(currency -> balances.put(currency, BigDecimal.ZERO));
            playerHistory.forEach(entry -> balances.merge(entry.getCurrency(), entry.getAmount(), BigDecimal::add));
            List<FinanceResponse.Bill> statements = new ArrayList<>();
            for (GameSession session : gameSessions) {
                SessionBill bill = knownBills.stream().filter(candidate -> candidate.getPlayerId().equals(playerId)
                        && candidate.getSessionId().equals(session.getId())).findFirst().orElse(null);
                SessionRegistration participation = attendance.stream().filter(candidate -> candidate.getPlayerId().equals(playerId)
                        && candidate.getSessionId().equals(session.getId())).findFirst().orElse(null);
                if (bill == null && (participation == null || isClosed(session))) continue;
                boolean paid = participation != null && participation.getPaidAt() != null;
                statements.add(new FinanceResponse.Bill(session.getId(), session.getTitle(), session.getStartsAt(),
                        bill == null ? session.getPriceCurrency() : bill.getCurrency(),
                        bill == null ? session.getPriceAmount() : bill.getAmount(),
                        bill == null ? (paid ? BigDecimal.ZERO : session.getPriceAmount()) : bill.remaining(),
                        bill == null ? BigDecimal.ZERO : bill.getClaimed(),
                        bill != null && bill.isFinalized(), bill != null && bill.isExempt()));
            }
            accounts.add(new FinanceResponse.Account(playerId, balances, playerHistory, statements));
        }
        return new FinanceResponse(accounts);
    }

    /** Добавляет пополнение или объяснённую корректировку, не меняя старые записи. */
    public void addEntry(UUID actorId, UUID gameId, UUID playerId, CreateFinanceEntryRequest request) {
        Game game = lock(gameId);
        requireMaster(game, actorId);
        try { Currency.getInstance(request.currency()); }
        catch (IllegalArgumentException exception) { throw invalid("Неизвестная валюта"); }
        if (request.amount().signum() == 0 || (request.kind().equals("TOP_UP") && request.amount().signum() < 0)) {
            throw invalid("Пополнение должно быть положительным, корректировка — ненулевой");
        }
        if (request.kind().equals("ADJUSTMENT") && (request.comment() == null || request.comment().isBlank())) {
            throw invalid("Укажите причину корректировки");
        }
        boolean knownPlayer = registrations.existsByGameIdAndPlayerIdAndStatus(gameId, playerId, RegistrationStatus.APPROVED)
                || !entries.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, playerId).isEmpty();
        if (!knownPlayer) throw invalid("Игрок не входит в состав игры и не имеет счёта");
        Optional<FinanceEntry> duplicate = entries.findById(request.operationId());
        if (duplicate.isPresent()) {
            FinanceEntry previous = duplicate.get();
            if (!previous.getGameId().equals(gameId) || !previous.getPlayerId().equals(playerId)
                    || previous.getAmount().compareTo(request.amount()) != 0 || !previous.getCurrency().equals(request.currency())
                    || !previous.getKind().equals(request.kind()) || !Objects.equals(previous.getComment(), request.comment())) {
                throw invalid("Этот ключ операции уже использован с другими значениями");
            }
            return;
        }
        entries.save(new FinanceEntry(request.operationId(), gameId, playerId, null, request.amount(),
                request.currency(), request.kind(), request.comment(), actorId));
        settleDebts(gameId, playerId, request.currency(), request.amount().max(BigDecimal.ZERO), actorId);
    }

    /** Списывает доступный аванс; недостаток не превращается в долг до завершения встречи. */
    public void payFromBalance(UUID playerId, UUID gameId, UUID sessionId) {
        lock(gameId);
        GameSession session = session(gameId, sessionId);
        SessionRegistration participation = participant(sessionId, playerId);
        SessionBill bill = bill(session, participation, playerId);
        requirePayable(session, bill);
        BigDecimal available = balance(gameId, playerId, bill.getCurrency()).max(BigDecimal.ZERO);
        BigDecimal amount = bill.remaining().min(available);
        if (amount.signum() == 0) return;
        bill.setSettled(bill.getSettled().add(amount));
        book(bill, bill.getSettled().max(bill.getBooked()), playerId, "RESERVATION");
        bill.setClaimed(bill.getClaimed().min(bill.remaining()));
        saveBill(bill);
    }

    /** Сообщение игрока не является подтверждением перевода и не изменяет баланс. */
    public void claimPayment(UUID playerId, UUID gameId, UUID sessionId) {
        lock(gameId);
        GameSession session = session(gameId, sessionId);
        SessionBill bill = bill(session, participant(sessionId, playerId), playerId);
        requirePayable(session, bill);
        bill.setClaimed(bill.remaining());
        saveBill(bill);
    }

    /** Подтверждает заявленную сумму, в том числе после начисления долга. */
    public void confirmPayment(UUID masterId, UUID gameId, UUID sessionId, UUID playerId, boolean confirm) {
        Game game = lock(gameId);
        requireMaster(game, masterId);
        GameSession session = session(gameId, sessionId);
        SessionBill bill = bill(session, participant(sessionId, playerId), masterId);
        requirePayable(session, bill);
        BigDecimal amount = bill.getClaimed().min(bill.remaining());
        if (confirm && amount.signum() > 0) {
            record(bill, amount, "PAYMENT", masterId);
            bill.setSettled(bill.getSettled().add(amount));
            book(bill, bill.getBooked().max(bill.getSettled()), masterId, "RESERVATION");
        }
        bill.setClaimed(BigDecimal.ZERO);
        saveBill(bill);
    }

    /** Старый переключатель оплаты не должен обходить журнал взаиморасчётов. */
    public void markPaid(UUID masterId, UUID gameId, UUID sessionId, UUID playerId, boolean paid) {
        Game game = lock(gameId);
        requireMaster(game, masterId);
        GameSession session = session(gameId, sessionId);
        SessionBill bill = bill(session, participant(sessionId, playerId), masterId);
        requirePayable(session, bill);
        if (!paid) throw invalid("Для исправления оплаты используйте корректировку во вкладке «Финансы»");
        bill.setClaimed(bill.remaining());
        bills.save(bill);
        confirmPayment(masterId, gameId, sessionId, playerId, true);
    }

    /** Вызывается в транзакции завершения с уже заблокированной игрой. */
    public void finish(Game game, GameSession session, boolean cancelled, UUID actorId) {
        if (game.getCostType() != GameCostType.PAID) return;
        for (SessionRegistration participation : participants.findAllBySessionIdOrderByCreatedAtAsc(session.getId())) {
            SessionBill bill = bills.findBySessionIdAndPlayerId(session.getId(), participation.getPlayerId())
                    .orElseGet(() -> newBill(session, participation, actorId));
            if (bill.isFinalized()) continue;
            bill.setExempt(false);
            if (cancelled || participation.getAttendanceStatus() != SessionAttendanceStatus.ATTENDING) {
                exempt(bill, actorId);
            } else {
                BigDecimal available = balance(game.getId(), participation.getPlayerId(), bill.getCurrency()).max(BigDecimal.ZERO);
                bill.setSettled(bill.getSettled().add(bill.remaining().min(available)));
                bill.setClaimed(bill.getClaimed().min(bill.remaining()));
                book(bill, bill.getAmount(), actorId, "SESSION_CHARGE");
                bill.setFinalized(true);
                saveBill(bill);
            }
        }
    }

    /** Возвращает аванс при исключении или выходе игрока из незакрытых встреч. */
    public void release(UUID gameId, UUID playerId, List<UUID> sessionIds, UUID actorId) {
        for (SessionBill bill : bills.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, playerId)) {
            if (sessionIds.contains(bill.getSessionId()) && !bill.isFinalized()) {
                exempt(bill, actorId);
                bill.setFinalized(false);
                bills.save(bill);
            }
        }
    }

    /** Снимает начисление; подтверждённые внешние платежи остаются авансом игрока. */
    private void exempt(SessionBill bill, UUID actorId) {
        BigDecimal released = bill.getBooked();
        book(bill, BigDecimal.ZERO, actorId, "RELEASE");
        bill.setExempt(true);
        bill.setFinalized(true);
        bill.setSettled(BigDecimal.ZERO);
        bill.setClaimed(BigDecimal.ZERO);
        saveBill(bill);
        settleDebts(bill.getGameId(), bill.getPlayerId(), bill.getCurrency(), released, actorId);
    }

    /** Пополнение сначала погашает старые начисленные долги в той же валюте. */
    private void settleDebts(UUID gameId, UUID playerId, String currency, BigDecimal credit, UUID actorId) {
        BigDecimal remainingCredit = credit;
        for (SessionBill bill : bills.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, playerId)) {
            if (!bill.isFinalized() || bill.isExempt() || !currency.equals(bill.getCurrency())) continue;
            BigDecimal allocated = bill.remaining().min(remainingCredit);
            bill.setSettled(bill.getSettled().add(allocated));
            bill.setClaimed(bill.getClaimed().min(bill.remaining()));
            if (allocated.signum() > 0) record(bill, allocated.negate(), "DEBT_SETTLEMENT", actorId);
            remainingCredit = remainingCredit.subtract(allocated);
            saveBill(bill);
            if (remainingCredit.signum() == 0) break;
        }
    }

    /** Фиксирует только разницу начисления: повторный вызов ничего не списывает. */
    private void book(SessionBill bill, BigDecimal target, UUID actorId, String kind) {
        BigDecimal delta = bill.getBooked().subtract(target);
        if (delta.signum() != 0) record(bill, delta, kind, actorId);
        bill.setBooked(target);
    }

    /** Добавляет неизменяемую строку в историю счёта. */
    private void record(SessionBill bill, BigDecimal amount, String kind, UUID actorId) {
        entries.save(new FinanceEntry(UUID.randomUUID(), bill.getGameId(), bill.getPlayerId(), bill.getSessionId(),
                amount, bill.getCurrency(), kind, null, actorId));
    }

    /** Синхронизирует старый признак оплаты для существующих карточек сессий. */
    private void saveBill(SessionBill bill) {
        bills.save(bill);
        participants.findBySessionIdAndPlayerId(bill.getSessionId(), bill.getPlayerId()).ifPresent(participation -> {
            participation.setPaidAt(!bill.isExempt() && bill.remaining().signum() == 0 ? Instant.now() : null);
            participants.save(participation);
        });
    }

    /** Не создаёт задним числом начисления для встреч до появления бухгалтерии. */
    private SessionBill bill(GameSession session, SessionRegistration participation, UUID actorId) {
        SessionBill bill = bills.findBySessionIdAndPlayerId(session.getId(), participation.getPlayerId()).orElseGet(() -> {
            if (isClosed(session)) throw invalid("Для старой закрытой сессии расчёт не ведётся");
            return newBill(session, participation, actorId);
        });
        if (!bill.isFinalized() && !isClosed(session)) bill.setExempt(false);
        return bill;
    }

    /** Снимок цены не меняется после начала расчётов; прежняя подтверждённая оплата сохраняется. */
    private SessionBill newBill(GameSession session, SessionRegistration participation, UUID actorId) {
        SessionBill bill = new SessionBill();
        bill.setGameId(session.getGameId());
        bill.setSessionId(session.getId());
        bill.setPlayerId(participation.getPlayerId());
        bill.setAmount(session.getPriceAmount());
        bill.setCurrency(session.getPriceCurrency());
        if (participation.getPaidAt() != null) {
            record(bill, bill.getAmount(), "LEGACY_PAYMENT", actorId);
            bill.setSettled(bill.getAmount());
            book(bill, bill.getAmount(), actorId, "RESERVATION");
        }
        return bills.save(bill);
    }

    /** Баланс всегда выводится из журнала, отдельно для каждой валюты. */
    private BigDecimal balance(UUID gameId, UUID playerId, String currency) {
        return entries.findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(gameId, playerId).stream()
                .filter(entry -> entry.getCurrency().equals(currency)).map(FinanceEntry::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Единый порядок блокировки защищает списания и завершение от гонок. */
    private Game lock(UUID gameId) {
        Game game = games.findByIdForUpdate(gameId).orElseThrow(() -> new GameNotFoundException(gameId));
        requirePaid(game);
        return game;
    }

    /** Проверяет принадлежность сессии игре. */
    private GameSession session(UUID gameId, UUID sessionId) {
        return sessions.findByIdAndGameId(sessionId, gameId).orElseThrow(() -> new GameSessionNotFoundException(sessionId));
    }

    /** Игрок не может оплатить чужое участие. */
    private SessionRegistration participant(UUID sessionId, UUID playerId) {
        return participants.findBySessionIdAndPlayerId(sessionId, playerId)
                .orElseThrow(() -> new SessionRegistrationAccessDeniedException("Игрок не участвует в сессии"));
    }

    /** Проверяет право владельца на бухгалтерию. */
    private static void requireMaster(Game game, UUID actorId) {
        if (!game.getMasterId().equals(actorId)) throw new SessionRegistrationAccessDeniedException("Финансы доступны только мастеру");
    }

    /** Бесплатные игры не имеют бухгалтерии. */
    private static void requirePaid(Game game) {
        if (game.getCostType() != GameCostType.PAID) throw invalid("Финансы доступны только для платных игр");
    }

    /** Отменённую встречу и освобождённое участие оплачивать нельзя. */
    private static void requirePayable(GameSession session, SessionBill bill) {
        if (session.getStatus() == GameSessionStatus.CANCELLED || bill.isExempt()) throw invalid("Оплата этой сессии не требуется");
    }

    /** Определяет закрытую встречу без зависимости от даты. */
    private static boolean isClosed(GameSession session) {
        return session.getStatus() == GameSessionStatus.COMPLETED || session.getStatus() == GameSessionStatus.CANCELLED;
    }

    /** Доменный отказ отображается клиенту как ошибка запроса. */
    private static InvalidSessionRegistrationException invalid(String message) {
        return new InvalidSessionRegistrationException(message);
    }
}
