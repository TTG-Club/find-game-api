package club.ttg.findgame.registration;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Заявки в игру.
 *
 * Игрок записывается в игру целиком, а не в каждую сессию: принятый входит в
 * состав и попадает во все запланированные встречи, включая созданные позже.
 * Участие в сессии заводит сервис — оно хранит присутствие и оплату.
 */
@Service
public class GameRegistrationService {

    private final GameRepository gameRepository;
    private final GameSessionRepository sessionRepository;
    private final GameRegistrationRepository registrationRepository;
    private final SessionRegistrationRepository participantRepository;
    private final NotificationService notificationService;

    public GameRegistrationService(
            GameRepository gameRepository,
            GameSessionRepository sessionRepository,
            GameRegistrationRepository registrationRepository,
            SessionRegistrationRepository participantRepository,
            NotificationService notificationService
    ) {
        this.gameRepository = gameRepository;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.participantRepository = participantRepository;
        this.notificationService = notificationService;
    }

    /**
     * Подаёт заявку в игру.
     *
     * @param playerId Игрок из токена.
     * @param gameId Игра.
     * @param inviteCode Код приглашения из адреса страницы; нужен приватной игре.
     * @param request Как игрок представил персонажа.
     * @return Поданная заявка.
     */
    @Transactional
    public GameRegistrationResponse register(
            UUID playerId,
            UUID gameId,
            UUID inviteCode,
            CreateGameRegistrationRequest request
    ) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireVisible(game, inviteCode);

        if (game.getMasterId().equals(playerId)) {
            throw new InvalidSessionRegistrationException(
                    "Мастер не может подать заявку в собственную игру");
        }
        if (registrationRepository.findByGameIdAndPlayerId(gameId, playerId).isPresent()) {
            throw new InvalidSessionRegistrationException("Игрок уже подал заявку в эту игру");
        }

        GameRegistration registration = new GameRegistration();
        registration.setGameId(gameId);
        registration.setPlayerId(playerId);
        registration.setCharacterSheetUrl(request.characterSheetUrl());
        registration.setCharacterName(request.characterName());
        registration.setStatus(RegistrationStatus.PENDING);

        GameRegistration saved;

        try {
            saved = registrationRepository.saveAndFlush(registration);
        } catch (DataIntegrityViolationException exception) {
            throw new InvalidSessionRegistrationException("Игрок уже подал заявку в эту игру");
        }

        notificationService.notifyUser(
                game.getMasterId(), playerId, NotificationType.REGISTRATION_SUBMITTED,
                game.getId(), game.getTitle(), null, null);

        return toResponse(saved);
    }

    /**
     * Отзывает собственную заявку. Отозванная удаляется, а не помечается:
     * отказ мастера и передумавший игрок — разные вещи, и на отклонённую
     * заявку подать повторно уже нельзя, а на отозванную — можно.
     *
     * Принятую так не отзывают: место согласовано, и тихий уход из состава
     * подвёл бы группу — об этом договариваются с мастером.
     *
     * @param playerId Игрок из токена.
     * @param gameId Игра.
     */
    @Transactional
    public void withdraw(UUID playerId, UUID gameId) {
        GameRegistration registration = registrationRepository
                .findByGameIdAndPlayerId(gameId, playerId)
                .orElseThrow(() -> new SessionRegistrationNotFoundException(gameId));

        if (registration.getStatus() == RegistrationStatus.APPROVED) {
            throw new InvalidSessionRegistrationException(
                    "Принятую заявку отзывает мастер: договоритесь с ним");
        }

        registrationRepository.delete(registration);
    }

    /**
     * Решение мастера по заявке.
     *
     * Принятый игрок сразу попадает во все запланированные сессии, а
     * исключённый уходит из всех незакрытых: в сыгранных и отменённых его
     * участие остаётся историей.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @param registrationId Заявка.
     * @param request Решение мастера.
     * @return Заявка после решения.
     */
    @Transactional
    public GameRegistrationResponse review(
            UUID masterId,
            UUID gameId,
            UUID registrationId,
            ReviewGameRegistrationRequest request
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireMaster(game, masterId);

        GameRegistration registration = registrationRepository
                .findByIdAndGameId(registrationId, gameId)
                .orElseThrow(() -> new SessionRegistrationNotFoundException(registrationId));

        if (request.decision() == RegistrationDecision.APPROVE) {
            approve(game, registration);
        } else {
            reject(gameId, registration);
        }

        GameRegistration saved = registrationRepository.save(registration);

        if (saved.getStatus() == RegistrationStatus.APPROVED) {
            notificationService.notifyUser(
                    saved.getPlayerId(), masterId, NotificationType.REGISTRATION_APPROVED,
                    game.getId(), game.getTitle(), null, null);
        }

        return toResponse(saved);
    }

    /**
     * Заявки игры глазами мастера.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @return Заявки в порядке подачи.
     */
    @Transactional(readOnly = true)
    public List<GameRegistrationResponse> findAllForMaster(UUID masterId, UUID gameId) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireMaster(game, masterId);

        return registrationRepository.findAllByGameIdOrderByCreatedAtAsc(gameId).stream()
                .map(GameRegistrationService::toResponse)
                .toList();
    }

    /**
     * Собственная заявка игрока.
     *
     * @param playerId Игрок из токена.
     * @param gameId Игра.
     * @param inviteCode Код приглашения из адреса страницы.
     * @return Заявка игрока.
     */
    @Transactional(readOnly = true)
    public GameRegistrationResponse findOwn(UUID playerId, UUID gameId, UUID inviteCode) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireVisible(game, inviteCode);

        return registrationRepository.findByGameIdAndPlayerId(gameId, playerId)
                .map(GameRegistrationService::toResponse)
                .orElseThrow(() -> new SessionRegistrationNotFoundException(gameId));
    }

    /**
     * Игроки, принятые в игру.
     *
     * @param gameId Игра.
     * @return Идентификаторы принятых игроков.
     */
    @Transactional(readOnly = true)
    public List<UUID> approvedPlayerIds(UUID gameId) {
        return registrationRepository
                .findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED).stream()
                .map(GameRegistration::getPlayerId)
                .toList();
    }

    private void approve(Game game, GameRegistration registration) {
        boolean alreadyApproved = registration.getStatus() == RegistrationStatus.APPROVED;

        if (!alreadyApproved
                && registrationRepository.countByGameIdAndStatus(
                        game.getId(), RegistrationStatus.APPROVED) >= game.getMaxPlayers()) {
            throw new InvalidSessionRegistrationException(
                    "В игре достигнуто максимальное количество игроков");
        }

        registration.setStatus(RegistrationStatus.APPROVED);
        addToScheduledSessions(game.getId(), registration.getPlayerId());
    }

    private void reject(UUID gameId, GameRegistration registration) {
        registration.setStatus(RegistrationStatus.REJECTED);

        List<UUID> openSessions = sessionRepository
                .findAllByGameIdOrderByStartsAtAsc(gameId).stream()
                .filter(session -> session.getStatus() == GameSessionStatus.SCHEDULED
                        || session.getStatus() == GameSessionStatus.IN_PROGRESS)
                .map(GameSession::getId)
                .toList();

        if (!openSessions.isEmpty()) {
            participantRepository.deleteBySessionIdInAndPlayerId(
                    openSessions, registration.getPlayerId());
        }
    }

    /** Заводит участие игрока во всех запланированных сессиях игры. */
    private void addToScheduledSessions(UUID gameId, UUID playerId) {
        List<SessionRegistration> participations = sessionRepository
                .findAllByGameIdOrderByStartsAtAsc(gameId).stream()
                .filter(session -> session.getStatus() == GameSessionStatus.SCHEDULED)
                .filter(session -> !participantRepository.existsBySessionIdAndPlayerId(
                        session.getId(), playerId))
                .map(session -> SessionRegistration.of(session.getId(), playerId))
                .toList();

        if (!participations.isEmpty()) {
            participantRepository.saveAll(participations);
        }
    }

    private void requireMaster(Game game, UUID masterId) {
        if (!game.getMasterId().equals(masterId)) {
            throw new SessionRegistrationAccessDeniedException("Это чужая игра");
        }
    }

    /** Приватную игру видно только по коду приглашения. */
    private void requireVisible(Game game, UUID inviteCode) {
        if (game.getVisibility() == GameVisibility.PUBLIC) {
            return;
        }
        if (inviteCode == null || !inviteCode.equals(game.getInviteCode())) {
            throw new GameNotFoundException(game.getId());
        }
    }

    private static GameRegistrationResponse toResponse(GameRegistration registration) {
        return new GameRegistrationResponse(
                registration.getId(),
                registration.getGameId(),
                registration.getPlayerId(),
                registration.getCharacterSheetUrl(),
                registration.getCharacterName(),
                registration.getStatus(),
                registration.getCreatedAt(),
                registration.getUpdatedAt());
    }
}
