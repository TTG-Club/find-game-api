package club.ttg.findgame.session;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameVisibility;
import club.ttg.findgame.chat.ChatService;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistration;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.session.api.CreateGameSessionRequest;
import club.ttg.findgame.session.api.CopyGameSessionRequest;
import club.ttg.findgame.session.api.GameSessionResponse;
import club.ttg.findgame.session.api.ScheduleGameSessionRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
            ChatService chatService
    ) {
        this.gameRepository = gameRepository;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.gameRegistrationRepository = gameRegistrationRepository;
        this.mapper = mapper;
        this.notificationService = notificationService;
        this.chatService = chatService;
    }

    @Transactional
    public GameSessionResponse create(UUID masterId, UUID gameId, CreateGameSessionRequest request) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (!game.getMasterId().equals(masterId)) {
            throw new GameSessionAccessDeniedException();
        }
        validateCost(game.getCostType(), request);

        GameSession session = mapper.toEntity(request);
        session.setGameId(gameId);
        session.setStatus(GameSessionStatus.SCHEDULED);
        GameSession saved = sessionRepository.save(session);

        // Состав игры въезжает в новую сессию сразу: игрок записывался в игру,
        // и заново подавать заявку на каждую встречу ему не нужно.
        return toResponse(saved, addApprovedPlayers(gameId, saved.getId()));
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
        chatService.publishSystem(gameId, sessionId, masterId, SESSION_STARTED_MESSAGE);

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

        Set<UUID> players = approvedPlayerIds(sessionId);
        GameSessionResponse response = toResponse(sessionRepository.save(session), players);

        notifyPlayers(owned, masterId, players, NotificationType.SESSION_COMPLETED);
        chatService.publishSystem(gameId, sessionId, masterId, SESSION_COMPLETED_MESSAGE);

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
        chatService.publishSystem(gameId, sessionId, masterId, SESSION_CANCELLED_MESSAGE);

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

    /**
     * Назначает дату сессии, объявленной с открытой датой.
     *
     * Отдельным методом, а не общей правкой сессии: это единственное, что
     * мастеру нужно поменять после набора, и у изменения свой смысл — закрыть
     * открытую дату. Уже назначенное время так не двигают: игроки под него
     * подстроились, и тихий перенос их бы подвёл.
     */
    @Transactional
    public GameSessionResponse schedule(
            UUID masterId,
            UUID gameId,
            UUID sessionId,
            ScheduleGameSessionRequest request
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (!game.getMasterId().equals(masterId)) {
            throw new GameSessionAccessDeniedException();
        }
        GameSession session = sessionRepository.findByIdAndGameId(sessionId, gameId)
                .orElseThrow(() -> new GameSessionNotFoundException(sessionId));
        if (session.getStartsAt() != null) {
            throw new InvalidGameSessionDateException("Дата сессии уже назначена");
        }

        session.setStartsAt(request.startsAt());
        GameSession saved = sessionRepository.save(session);

        return toResponse(saved, approvedPlayerIds(sessionId));
    }

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
    private void validateCost(GameCostType costType, CreateGameSessionRequest request) {
        int filled = (request.priceAmount() != null ? 1 : 0)
                + (request.priceCurrency() != null ? 1 : 0)
                + (request.paymentType() != null ? 1 : 0);
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
                response.priceAmount(), response.priceCurrency(), response.paymentType(), registeredPlayerIds);
    }
}
