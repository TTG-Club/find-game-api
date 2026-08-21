package club.ttg.findgame.session;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameCostType;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameVisibility;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistration;
import club.ttg.findgame.registration.SessionRegistrationStatus;
import club.ttg.findgame.session.api.CreateGameSessionRequest;
import club.ttg.findgame.session.api.CopyGameSessionRequest;
import club.ttg.findgame.session.api.GameSessionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class GameSessionService {

    private final GameRepository gameRepository;
    private final GameSessionRepository sessionRepository;
    private final SessionRegistrationRepository registrationRepository;
    private final GameSessionMapper mapper;

    public GameSessionService(
            GameRepository gameRepository,
            GameSessionRepository sessionRepository,
            SessionRegistrationRepository registrationRepository,
            GameSessionMapper mapper
    ) {
        this.gameRepository = gameRepository;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.mapper = mapper;
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
        return toResponse(sessionRepository.save(session), Set.of());
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

        List<SessionRegistration> approvedRegistrations =
                registrationRepository.findAllBySessionIdInAndStatus(
                        List.of(sourceSessionId), SessionRegistrationStatus.APPROVED);
        UUID targetSessionId = target.getId();
        List<SessionRegistration> copiedRegistrations = approvedRegistrations.stream()
                .map(registration -> SessionRegistration.copyApprovedTo(targetSessionId, registration))
                .toList();
        registrationRepository.saveAll(copiedRegistrations);

        Set<UUID> copiedPlayerIds = approvedRegistrations.stream()
                .map(SessionRegistration::getPlayerId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        return toResponse(target, copiedPlayerIds);
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

    private void validateCost(GameCostType costType, CreateGameSessionRequest request) {
        boolean hasAnyCostField = request.priceAmount() != null
                || request.priceCurrency() != null
                || request.paymentType() != null;
        if (costType == GameCostType.FREE && hasAnyCostField) {
            throw new InvalidGameSessionCostException(
                    "Для сессии бесплатной игры сумма, валюта и тип оплаты не указываются");
        }
        if (costType == GameCostType.PAID
                && (request.priceAmount() == null
                || request.priceCurrency() == null
                || request.paymentType() == null)) {
            throw new InvalidGameSessionCostException(
                    "Для сессии платной игры необходимо указать сумму, валюту и тип оплаты");
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

    private Map<UUID, Set<UUID>> approvedPlayersBySession(List<GameSession> sessions) {
        if (sessions.isEmpty()) {
            return Map.of();
        }
        List<UUID> sessionIds = sessions.stream().map(GameSession::getId).toList();
        Map<UUID, Set<UUID>> result = new LinkedHashMap<>();
        registrationRepository.findAllBySessionIdInAndStatus(
                        sessionIds, SessionRegistrationStatus.APPROVED)
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
