package club.ttg.findgame.session;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameVisibility;
import club.ttg.findgame.chat.ChatService;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import club.ttg.findgame.nexus.NexusService;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistration;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.session.api.CreateGameSessionRequest;
import club.ttg.findgame.session.api.CreateGameSessionSeriesRequest;
import club.ttg.findgame.session.api.CopyGameSessionRequest;
import club.ttg.findgame.session.api.GameSessionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GameSessionService {

    private final GameRepository gameRepository;
    private final GameSessionRepository sessionRepository;
    private final SessionRegistrationRepository registrationRepository;
    private final GameRegistrationRepository gameRegistrationRepository;
    private final GameSessionMapper mapper;
    private final NotificationService notificationService;
    private final ChatService chatService;
    private final NexusService nexusService;

    /**
     * Предел серии. Расписание на год вперёд — это уже не расписание, а
     * заявка на сотни встреч, которые никто не отменит вручную.
     */
    private static final int MAX_SERIES_SESSIONS = 100;

    /** Тексты событий, которые сервис пишет в чат сессии. */
    private static final String SESSION_STARTED_MESSAGE = "Сессия началась";
    private static final String SESSION_COMPLETED_MESSAGE = "Сессия завершена";
    private static final String SESSION_CANCELLED_MESSAGE = "Сессия отменена";

    public GameSessionService(
            GameRepository gameRepository,
            GameSessionRepository sessionRepository,
            SessionRegistrationRepository registrationRepository,
            GameRegistrationRepository gameRegistrationRepository,
            GameSessionMapper mapper,
            NotificationService notificationService,
            ChatService chatService,
            NexusService nexusService
    ) {
        this.gameRepository = gameRepository;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.gameRegistrationRepository = gameRegistrationRepository;
        this.mapper = mapper;
        this.notificationService = notificationService;
        this.chatService = chatService;
        this.nexusService = nexusService;
    }

    @Transactional
    public GameSessionResponse create(UUID masterId, UUID gameId, CreateGameSessionRequest request) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (!game.getMasterId().equals(masterId)) {
            throw new GameSessionAccessDeniedException();
        }
        validateCost(game.getCostType(), request.priceAmount(),
                request.priceCurrency(), request.paymentType());

        GameSession session = mapper.toEntity(request);
        session.setGameId(gameId);
        session.setStatus(GameSessionStatus.SCHEDULED);
        GameSession saved = sessionRepository.save(session);

        // Состав игры въезжает в новую сессию сразу: игрок записывался в игру,
        // и заново подавать заявку на каждую встречу ему не нужно.
        return toResponse(saved, addApprovedPlayers(gameId, saved.getId()));
    }

    /**
     * Заводит серию встреч по расписанию.
     *
     * Каждая встреча — обычная сессия: серия не заводит своей сущности, иначе
     * отмена одной встречи тянула бы за собой вопрос, что стало с расписанием.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @param request Расписание серии.
     * @return Созданные встречи в порядке времени.
     */
    @Transactional
    public List<GameSessionResponse> createSeries(
            UUID masterId,
            UUID gameId,
            CreateGameSessionSeriesRequest request
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (!game.getMasterId().equals(masterId)) {
            throw new GameSessionAccessDeniedException();
        }

        validateCost(game.getCostType(), request.priceAmount(),
                request.priceCurrency(), request.paymentType());

        List<Instant> starts = seriesStarts(request);

        List<GameSessionResponse> created = new ArrayList<>(starts.size());

        for (Instant startsAt : starts) {
            GameSession session = new GameSession();
            session.setGameId(gameId);
            session.setTitle(request.title());
            session.setStartsAt(startsAt);
            session.setEstimatedDurationMinutes(request.estimatedDurationMinutes());
            session.setStatus(GameSessionStatus.SCHEDULED);
            session.setPriceAmount(request.priceAmount());
            session.setPriceCurrency(request.priceCurrency());
            session.setPaymentType(request.paymentType());

            GameSession saved = sessionRepository.save(session);

            created.add(toResponse(saved, addApprovedPlayers(gameId, saved.getId())));
        }

        return created;
    }

    /**
     * Раскладывает расписание серии на моменты начала встреч.
     *
     * Прошедшее отбрасывается: серию заводят на будущее, и встреча, начало
     * которой уже позади, была бы мусором в расписании.
     */
    private static List<Instant> seriesStarts(CreateGameSessionSeriesRequest request) {
        if (request.until().isBefore(request.startsOn())) {
            throw new InvalidGameSessionDateException(
                    "Конец серии не может быть раньше её начала");
        }

        ZoneId zone;

        try {
            zone = ZoneId.of(request.zoneId());
        } catch (DateTimeException exception) {
            throw new InvalidGameSessionDateException("Неизвестный часовой пояс");
        }

        Instant now = Instant.now();
        List<Instant> starts = new ArrayList<>();

        for (LocalDate day = request.startsOn();
                !day.isAfter(request.until());
                day = day.plusDays(1)) {
            if (!request.daysOfWeek().contains(day.getDayOfWeek())) {
                continue;
            }

            Instant startsAt = day.atTime(request.timeOfDay()).atZone(zone).toInstant();

            if (startsAt.isBefore(now)) {
                continue;
            }

            starts.add(startsAt);

            if (starts.size() > MAX_SERIES_SESSIONS) {
                throw new InvalidGameSessionDateException(
                        "За раз создаётся не больше " + MAX_SERIES_SESSIONS + " встреч");
            }
        }

        if (starts.isEmpty()) {
            throw new InvalidGameSessionDateException(
                    "В выбранном промежутке нет ни одного подходящего дня");
        }

        return starts;
    }

    @Transactional
    public GameSessionResponse copy(
            UUID masterId,
            UUID gameId,
            UUID sourceSessionId,
            CopyGameSessionRequest request
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (!game.getMasterId().equals(masterId)) {
            throw new GameSessionAccessDeniedException();
        }
        GameSession source = sessionRepository.findByIdAndGameId(sourceSessionId, gameId)
                .orElseThrow(() -> new GameSessionNotFoundException(sourceSessionId));
        GameSession target = copySession(source, request);
        target = sessionRepository.save(target);

        // Состав копии — принятые в игру, а не участники исходной сессии:
        // заявка подаётся в игру, и с прошлой встречи состав мог смениться.
        UUID targetSessionId = target.getId();
        List<UUID> players = gameRegistrationRepository
                .findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED).stream()
                .map(GameRegistration::getPlayerId)
                .toList();
        registrationRepository.saveAll(players.stream()
                .map(playerId -> SessionRegistration.of(targetSessionId, playerId))
                .toList());

        Set<UUID> copiedPlayerIds = new LinkedHashSet<>(players);
        return toResponse(target, copiedPlayerIds);
    }

    /**
     * Переводит сессию в «идёт». Отсюда открывается чат сессии: до начала
     * игрокам обсуждать нечего, а набор ещё может смениться.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @param sessionId Сессия.
     * @return Начатая сессия.
     */
    @Transactional
    public GameSessionResponse start(UUID masterId, UUID gameId, UUID sessionId) {
        OwnedSession owned = ownSession(masterId, gameId, sessionId);
        GameSession session = owned.session();
        if (session.getStatus() != GameSessionStatus.SCHEDULED) {
            throw new InvalidGameSessionStateException(
                    "Начать можно только запланированную сессию");
        }

        session.setStatus(GameSessionStatus.IN_PROGRESS);

        Set<UUID> players = approvedPlayerIds(sessionId);
        GameSessionResponse response = toResponse(sessionRepository.save(session), players);

        notifyPlayers(owned, masterId, players, NotificationType.SESSION_STARTED);
        publishToNexus(gameId, masterId, SESSION_STARTED_MESSAGE);

        return response;
    }

    /**
     * Завершает сессию: она сыграна. Уходит из предстоящих, места в ней
     * больше не занимаются, заявки остаются как история. Завершить можно и не
     * начатую — мастер не обязан отмечать начало.
     *
     * Несостоявшуюся сессию закрывают отменой: {@link #cancel}.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @param sessionId Сессия.
     * @return Завершённая сессия.
     */
    @Transactional
    public GameSessionResponse complete(UUID masterId, UUID gameId, UUID sessionId) {
        OwnedSession owned = ownSession(masterId, gameId, sessionId);
        GameSession session = owned.session();
        if (session.getStatus() == GameSessionStatus.COMPLETED
                || session.getStatus() == GameSessionStatus.CANCELLED) {
            throw new InvalidGameSessionStateException("Сессия уже закрыта");
        }

        session.setStatus(GameSessionStatus.COMPLETED);
        session.setCompletedAt(Instant.now());

        Set<UUID> players = approvedPlayerIds(sessionId);
        GameSessionResponse response = toResponse(sessionRepository.save(session), players);

        notifyPlayers(owned, masterId, players, NotificationType.SESSION_COMPLETED);
        publishToNexus(gameId, masterId, SESSION_COMPLETED_MESSAGE);

        return response;
    }

    /**
     * Отменяет сессию: она не состоялась. От завершения отличается только
     * исходом, но игроку разница важна — по завершённым видно, что было
     * сыграно.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @param sessionId Сессия.
     * @return Отменённая сессия.
     */
    @Transactional
    public GameSessionResponse cancel(UUID masterId, UUID gameId, UUID sessionId) {
        OwnedSession owned = ownSession(masterId, gameId, sessionId);
        GameSession session = owned.session();
        if (session.getStatus() == GameSessionStatus.COMPLETED
                || session.getStatus() == GameSessionStatus.CANCELLED) {
            throw new InvalidGameSessionStateException("Сессия уже закрыта");
        }

        session.setStatus(GameSessionStatus.CANCELLED);

        Set<UUID> players = approvedPlayerIds(sessionId);
        GameSessionResponse response = toResponse(sessionRepository.save(session), players);

        notifyPlayers(owned, masterId, players, NotificationType.SESSION_CANCELLED);
        publishToNexus(gameId, masterId, SESSION_CANCELLED_MESSAGE);

        return response;
    }

    /** Сессия своей игры вместе с игрой: чужую мастер не трогает. */
    private OwnedSession ownSession(UUID masterId, UUID gameId, UUID sessionId) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (!game.getMasterId().equals(masterId)) {
            throw new GameSessionAccessDeniedException();
        }

        GameSession session = sessionRepository.findByIdAndGameId(sessionId, gameId)
                .orElseThrow(() -> new GameSessionNotFoundException(sessionId));

        return new OwnedSession(game, session);
    }

    /**
     * Пишет событие игры в чат её комнаты.
     *
     * Комната заводится при первом входе, и до него её может не быть: писать
     * тогда некуда, а создавать комнату ради системной отметки незачем — тот,
     * кто в неё зайдёт, увидит уведомление и расписание.
     */
    private void publishToNexus(UUID gameId, UUID masterId, String text) {
        nexusService.findGameNexusId(gameId).ifPresent(nexusId ->
                chatService.publishSystem(nexusId, masterId, text));
    }

    /** Игра и её сессия — нужны вместе, чтобы уведомление знало название. */
    private record OwnedSession(Game game, GameSession session) {
    }

    /** Сообщает принятым игрокам о смене состояния сессии. */
    private void notifyPlayers(
            OwnedSession owned,
            UUID masterId,
            Set<UUID> players,
            NotificationType type
    ) {
        notificationService.notifyUsers(
                players, masterId, type,
                owned.game().getId(), owned.game().getTitle(),
                owned.session().getId(), owned.session().getTitle());
    }

    /** Копия сессии: состав полей тот же, время и название задаёт запрос. */
    private GameSession copySession(GameSession source, CopyGameSessionRequest request) {
        GameSession target = new GameSession();
        target.setGameId(source.getGameId());
        target.setTitle(request.title() == null ? source.getTitle() : request.title());
        target.setStartsAt(request.startsAt());
        target.setEstimatedDurationMinutes(source.getEstimatedDurationMinutes());
        target.setStatus(GameSessionStatus.SCHEDULED);
        target.setPriceAmount(source.getPriceAmount());
        target.setPriceCurrency(source.getPriceCurrency());
        target.setPaymentType(source.getPaymentType());
        return target;
    }

    /**
     * Условия оплаты сессии.
     *
     * Платная игра не обязана быть платной целиком: мастер вправе провести
     * знакомство или отработку бесплатно, поэтому у её сессии платёжные поля
     * либо заданы все три, либо не заданы вовсе. Заполнить их наполовину
     * нельзя — сумма без валюты игроку ничего не говорит. У бесплатной игры
     * платных сессий не бывает.
     */
    private void validateCost(
            GameCostType costType,
            java.math.BigDecimal priceAmount,
            String priceCurrency,
            SessionPaymentType paymentType
    ) {
        int filled = (priceAmount != null ? 1 : 0)
                + (priceCurrency != null ? 1 : 0)
                + (paymentType != null ? 1 : 0);
        if (costType == GameCostType.FREE && filled > 0) {
            throw new InvalidGameSessionCostException(
                    "Для сессии бесплатной игры сумма, валюта и тип оплаты не указываются");
        }
        if (costType == GameCostType.PAID && filled > 0 && filled < 3) {
            throw new InvalidGameSessionCostException(
                    "У платной сессии нужны сумма, валюта и тип оплаты вместе");
        }
    }

    @Transactional(readOnly = true)
    public List<GameSessionResponse> findByGame(UUID requesterId, UUID gameId, UUID inviteCode) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        boolean owner = game.getMasterId().equals(requesterId);
        boolean publicGame = game.getVisibility() == GameVisibility.PUBLIC;
        boolean invited = inviteCode != null && inviteCode.equals(game.getInviteCode());
        if (!owner && !publicGame && !invited) {
            throw new GameNotFoundException(gameId);
        }

        List<GameSession> sessions = sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId);
        Map<UUID, Set<UUID>> approvedPlayers = approvedPlayersBySession(sessions);
        return sessions.stream()
                .map(session -> toResponse(
                        session, approvedPlayers.getOrDefault(session.getId(), Set.of())))
                .toList();
    }

/**
     * Заводит участие принятых в игру игроков в новой сессии.
     *
     * @param gameId Игра.
     * @param sessionId Новая сессия.
     * @return Идентификаторы добавленных игроков.
     */
    private Set<UUID> addApprovedPlayers(UUID gameId, UUID sessionId) {
        List<UUID> players = gameRegistrationRepository
                .findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED).stream()
                .map(GameRegistration::getPlayerId)
                .toList();

        if (players.isEmpty()) {
            return Set.of();
        }

        registrationRepository.saveAll(players.stream()
                .map(playerId -> SessionRegistration.of(sessionId, playerId))
                .toList());

        return new LinkedHashSet<>(players);
    }

    /** Участники сессии — их идентификаторы уходят в ответ. */
    private Set<UUID> approvedPlayerIds(UUID sessionId) {
        return registrationRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(SessionRegistration::getPlayerId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Map<UUID, Set<UUID>> approvedPlayersBySession(List<GameSession> sessions) {
        if (sessions.isEmpty()) {
            return Map.of();
        }
        List<UUID> sessionIds = sessions.stream().map(GameSession::getId).toList();
        Map<UUID, Set<UUID>> result = new LinkedHashMap<>();
        registrationRepository.findAllBySessionIdIn(sessionIds)
                .forEach(registration -> result
                        .computeIfAbsent(registration.getSessionId(), ignored -> new LinkedHashSet<>())
                        .add(registration.getPlayerId()));
        return result;
    }

    private GameSessionResponse toResponse(GameSession session, Set<UUID> registeredPlayerIds) {
        GameSessionResponse response = mapper.toResponse(session);
        return new GameSessionResponse(
                response.id(), response.gameId(), response.title(), response.startsAt(),
                response.estimatedDurationMinutes(), response.status(),
                response.priceAmount(), response.priceCurrency(), response.paymentType(),
                response.completedAt(), registeredPlayerIds);
    }
}
