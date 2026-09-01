package club.ttg.findgame.registration;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameVisibility;
import club.ttg.findgame.registration.api.SessionParticipantResponse;
import club.ttg.findgame.registration.api.UpdateAttendanceRequest;
import club.ttg.findgame.registration.api.UpdatePaymentStatusRequest;
import club.ttg.findgame.session.GameSessionNotFoundException;
import club.ttg.findgame.session.GameSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Участие в сессии: присутствие и оплата.
 *
 * Состав определяет заявка в игру, поэтому принимать и отклонять здесь нечего
 * — сервис работает только с тем, что относится к конкретной встрече.
 */
@Service
public class SessionRegistrationService {

    private final GameRepository gameRepository;
    private final GameSessionRepository sessionRepository;
    private final SessionRegistrationRepository participantRepository;

    public SessionRegistrationService(
            GameRepository gameRepository,
            GameSessionRepository sessionRepository,
            SessionRegistrationRepository participantRepository
    ) {
        this.gameRepository = gameRepository;
        this.sessionRepository = sessionRepository;
        this.participantRepository = participantRepository;
    }

    /**
     * Состав сессии глазами мастера.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @param sessionId Сессия.
     * @return Участники в порядке добавления.
     */
    @Transactional(readOnly = true)
    public List<SessionParticipantResponse> findAllForMaster(
            UUID masterId,
            UUID gameId,
            UUID sessionId
    ) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireMaster(game, masterId);
        requireSession(gameId, sessionId);

        return participantRepository.findAllBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(SessionRegistrationService::toResponse)
                .toList();
    }

    /**
     * Собственное участие игрока в сессии.
     *
     * @param playerId Игрок из токена.
     * @param gameId Игра.
     * @param sessionId Сессия.
     * @param inviteCode Код приглашения из адреса страницы.
     * @return Участие игрока.
     */
    @Transactional(readOnly = true)
    public SessionParticipantResponse findOwn(
            UUID playerId,
            UUID gameId,
            UUID sessionId,
            UUID inviteCode
    ) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireVisible(game, inviteCode);
        requireSession(gameId, sessionId);

        return participantRepository.findBySessionIdAndPlayerId(sessionId, playerId)
                .map(SessionRegistrationService::toResponse)
                .orElseThrow(() -> new SessionRegistrationNotFoundException(sessionId));
    }

    /**
     * Меняет собственное присутствие в сессии. Отмечать его может только тот,
     * кто в составе: участие заводится принятием заявки в игру.
     *
     * @param playerId Игрок из токена.
     * @param gameId Игра.
     * @param sessionId Сессия.
     * @param request Новый статус присутствия.
     * @return Участие после изменения.
     */
    @Transactional
    public SessionParticipantResponse updateAttendance(
            UUID playerId,
            UUID gameId,
            UUID sessionId,
            UpdateAttendanceRequest request
    ) {
        gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireSession(gameId, sessionId);

        SessionRegistration participation = participantRepository
                .findBySessionIdAndPlayerId(sessionId, playerId)
                .orElseThrow(() -> new InvalidSessionRegistrationException(
                        "Игрок не входит в состав этой сессии"));

        participation.setAttendanceStatus(request.attendanceStatus());

        return toResponse(participantRepository.save(participation));
    }

    /**
     * Отмечает оплату игрока. Только у платной игры и только мастером.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @param sessionId Сессия.
     * @param playerId Игрок, чью оплату отмечают.
     * @param request Оплачено или нет.
     * @return Участие после изменения.
     */
    @Transactional
    public SessionParticipantResponse updatePaymentStatus(
            UUID masterId,
            UUID gameId,
            UUID sessionId,
            UUID playerId,
            UpdatePaymentStatusRequest request
    ) {
        Game game = gameRepository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        requireMaster(game, masterId);

        if (game.getCostType() != GameCostType.PAID) {
            throw new InvalidSessionRegistrationException(
                    "Отмечать оплату можно только для платной игры");
        }

        requireSession(gameId, sessionId);

        SessionRegistration participation = participantRepository
                .findBySessionIdAndPlayerId(sessionId, playerId)
                .orElseThrow(() -> new SessionRegistrationNotFoundException(sessionId));

        participation.setPaidAt(Boolean.TRUE.equals(request.paid()) ? Instant.now() : null);

        return toResponse(participantRepository.save(participation));
    }

    private void requireSession(UUID gameId, UUID sessionId) {
        sessionRepository.findByIdAndGameId(sessionId, gameId)
                .orElseThrow(() -> new GameSessionNotFoundException(sessionId));
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

    private static SessionParticipantResponse toResponse(SessionRegistration participation) {
        return new SessionParticipantResponse(
                participation.getId(),
                participation.getSessionId(),
                participation.getPlayerId(),
                participation.getAttendanceStatus(),
                participation.getPaidAt() != null,
                participation.getPaidAt(),
                participation.getCreatedAt(),
                participation.getUpdatedAt());
    }
}
