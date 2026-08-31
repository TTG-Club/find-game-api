package club.ttg.findgame.registration;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import club.ttg.findgame.game.GameVisibility;
import club.ttg.findgame.registration.api.CreateSessionRegistrationRequest;
import club.ttg.findgame.registration.api.ReviewSessionRegistrationRequest;
import club.ttg.findgame.registration.api.SessionRegistrationResponse;
import club.ttg.findgame.registration.api.UpdateAttendanceRequest;
import club.ttg.findgame.registration.api.UpdatePaymentStatusRequest;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.session.GameSessionNotFoundException;
import club.ttg.findgame.session.GameSessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SessionRegistrationService {

    private final GameRepository gameRepository;
    private final GameSessionRepository sessionRepository;
    private final SessionRegistrationRepository registrationRepository;
    private final SessionRegistrationMapper mapper;
    private final NotificationService notificationService;

    public SessionRegistrationService(
            GameRepository gameRepository,
            GameSessionRepository sessionRepository,
            SessionRegistrationRepository registrationRepository,
            SessionRegistrationMapper mapper,
            NotificationService notificationService
    ) {
        this.gameRepository = gameRepository;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.mapper = mapper;
        this.notificationService = notificationService;
    }

    @Transactional
    public SessionRegistrationResponse register(
            UUID playerId,
            UUID gameId,
            UUID sessionId,
            UUID inviteCode,
            CreateSessionRegistrationRequest request
    ) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        GameSession session = findSession(gameId, sessionId);
        validatePlayerAccess(game, playerId, inviteCode);
        if (game.getMasterId().equals(playerId)) {
            throw new InvalidSessionRegistrationException("Мастер не может подать заявку в собственную игру");
        }
        if (session.getStatus() != GameSessionStatus.SCHEDULED) {
            throw new InvalidSessionRegistrationException("Регистрация доступна только на запланированную сессию");
        }
        if (registrationRepository.existsBySessionIdAndPlayerId(sessionId, playerId)) {
            throw new InvalidSessionRegistrationException("Игрок уже подал заявку на эту сессию");
        }

        SessionRegistration registration = mapper.toEntity(request);
        registration.setSessionId(sessionId);
        registration.setPlayerId(playerId);
        registration.setStatus(SessionRegistrationStatus.PENDING);
        SessionRegistration saved;

        try {
            saved = registrationRepository.saveAndFlush(registration);
        } catch (DataIntegrityViolationException exception) {
            throw new InvalidSessionRegistrationException("Игрок уже подал заявку на эту сессию");
        }

        notificationService.notifyUser(
                game.getMasterId(), playerId,
                NotificationType.REGISTRATION_SUBMITTED,
                game.getId(), game.getTitle(),
                session.getId(), session.getTitle());

        return mapper.toResponse(saved);
    }

    /**
     * Собственная заявка игрока на сессию. Нужна отдельным методом: список заявок
     * доступен только мастеру, а `registeredPlayerIds` сессии содержит лишь
     * принятых, поэтому по нему нельзя отличить `PENDING` от `REJECTED` и от
     * «заявки не было». Отсутствие заявки — 404, вызывающий трактует его как
     * «ещё не подавал».
     */
    @Transactional(readOnly = true)
    public SessionRegistrationResponse findOwn(UUID playerId, UUID gameId, UUID sessionId, UUID inviteCode) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        validatePlayerAccess(game, playerId, inviteCode);
        findSession(gameId, sessionId);
        return registrationRepository.findBySessionIdAndPlayerId(sessionId, playerId)
                .map(mapper::toResponse)
                .orElseThrow(() -> SessionRegistrationNotFoundException.forSession(sessionId));
    }

    @Transactional(readOnly = true)
    public List<SessionRegistrationResponse> findAllForMaster(UUID masterId, UUID gameId, UUID sessionId) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireMaster(game, masterId);
        findSession(gameId, sessionId);
        return registrationRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional
    public SessionRegistrationResponse review(
            UUID masterId,
            UUID gameId,
            UUID sessionId,
            UUID registrationId,
            ReviewSessionRegistrationRequest request
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireMaster(game, masterId);
        GameSession reviewed = findSession(gameId, sessionId);
        SessionRegistration registration = registrationRepository.findByIdAndSessionId(registrationId, sessionId)
                .orElseThrow(() -> new SessionRegistrationNotFoundException(registrationId));

        if (request.decision() == RegistrationDecision.APPROVE) {
            boolean alreadyApproved = registration.getStatus() == SessionRegistrationStatus.APPROVED;
            if (!alreadyApproved
                    && registrationRepository.countBySessionIdAndStatus(
                    sessionId, SessionRegistrationStatus.APPROVED) >= game.getMaxPlayers()) {
                throw new InvalidSessionRegistrationException("В сессии достигнуто максимальное количество игроков");
            }
            registration.setStatus(SessionRegistrationStatus.APPROVED);
            if (!alreadyApproved || registration.getAttendanceStatus() == null) {
                registration.setAttendanceStatus(SessionAttendanceStatus.NOT_ATTENDING);
            }
        } else {
            registration.setStatus(SessionRegistrationStatus.REJECTED);
            registration.setAttendanceStatus(null);
            registration.setPaidAt(null);
        }

        SessionRegistration saved = registrationRepository.save(registration);

        if (saved.getStatus() == SessionRegistrationStatus.APPROVED) {
            notificationService.notifyUser(
                    saved.getPlayerId(), masterId,
                    NotificationType.REGISTRATION_APPROVED,
                    game.getId(), game.getTitle(),
                    reviewed.getId(), reviewed.getTitle());
        }

        return mapper.toResponse(saved);
    }

    @Transactional
    public SessionRegistrationResponse updateAttendance(
            UUID playerId,
            UUID gameId,
            UUID sessionId,
            UpdateAttendanceRequest request
    ) {
        gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        findSession(gameId, sessionId);
        SessionRegistration registration = registrationRepository
                .findBySessionIdAndPlayerId(sessionId, playerId)
                .orElseThrow(() -> new InvalidSessionRegistrationException(
                        "Игрок не подавал заявку на эту сессию"));
        if (registration.getStatus() != SessionRegistrationStatus.APPROVED) {
            throw new InvalidSessionRegistrationException(
                    "Статус присутствия доступен только после принятия заявки мастером");
        }

        registration.setAttendanceStatus(request.attendanceStatus());
        return mapper.toResponse(registrationRepository.save(registration));
    }

    @Transactional
    public SessionRegistrationResponse updatePaymentStatus(
            UUID masterId,
            UUID gameId,
            UUID sessionId,
            UUID registrationId,
            UpdatePaymentStatusRequest request
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireMaster(game, masterId);
        if (game.getCostType() != GameCostType.PAID) {
            throw new InvalidSessionRegistrationException(
                    "Отмечать оплату можно только для платной игры");
        }
        findSession(gameId, sessionId);
        SessionRegistration registration = registrationRepository
                .findByIdAndSessionId(registrationId, sessionId)
                .orElseThrow(() -> new SessionRegistrationNotFoundException(registrationId));
        if (registration.getStatus() != SessionRegistrationStatus.APPROVED) {
            throw new InvalidSessionRegistrationException(
                    "Отмечать оплату можно только для принятого игрока");
        }

        if (Boolean.TRUE.equals(request.paid())) {
            if (registration.getPaidAt() == null) {
                registration.setPaidAt(Instant.now());
            }
        } else {
            registration.setPaidAt(null);
        }
        return mapper.toResponse(registrationRepository.save(registration));
    }

    private GameSession findSession(UUID gameId, UUID sessionId) {
        return sessionRepository.findByIdAndGameId(sessionId, gameId)
                .orElseThrow(() -> new GameSessionNotFoundException(sessionId));
    }

    private void validatePlayerAccess(Game game, UUID playerId, UUID inviteCode) {
        boolean publicGame = game.getVisibility() == GameVisibility.PUBLIC;
        boolean invited = inviteCode != null && inviteCode.equals(game.getInviteCode());
        boolean owner = game.getMasterId().equals(playerId);
        if (!publicGame && !invited && !owner) {
            throw new GameNotFoundException(game.getId());
        }
    }

    private void requireMaster(Game game, UUID masterId) {
        if (!game.getMasterId().equals(masterId)) {
            throw new SessionRegistrationAccessDeniedException(
                    "Только мастер-владелец может просматривать и обрабатывать заявки");
        }
    }
}
