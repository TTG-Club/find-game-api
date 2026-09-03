package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.FightStateRequest;
import club.ttg.findgame.nexus.api.FightStateResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Снимок идущего боя.
 *
 * Сам бой ведётся в core-api, и трекер открыт только тому, кто ведёт игру, —
 * значит очередь ходов группа увидеть не может. Клиент мастера складывает сюда
 * то, что за столом и так лежит на виду, а комната показывает по этому снимку
 * ту же карусель, что и трекер.
 */
@Service
public class NexusFightService {

    private final NexusTrackerRepository trackerRepository;
    private final NexusService nexusService;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public NexusFightService(
            NexusTrackerRepository trackerRepository,
            NexusService nexusService,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.trackerRepository = trackerRepository;
        this.nexusService = nexusService;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    /**
     * Обновляет снимок боя.
     *
     * Право у владельца комнаты: бой ведёт он, и пересказывать чужой ход
     * больше некому.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @param request Состояние боя на этот момент.
     * @return Снимок, каким его увидит группа.
     */
    @Transactional
    public FightStateResponse publish(
            UUID userId,
            UUID nexusId,
            FightStateRequest request
    ) {
        requireAccess(userId, nexusId);

        if (!nexusService.isOwner(nexusId, userId)) {
            throw new NexusAccessDeniedException("Бой ведёт владелец комнаты");
        }

        NexusTracker tracker = trackerRepository
                .findByNexusIdAndTrackerId(nexusId, request.trackerId())
                .orElseThrow(() -> new NexusNotFoundException("Трекер не найден в комнате"));

        tracker.setState(objectMapper.writeValueAsString(request));
        tracker.setStateUpdatedAt(Instant.now());

        FightStateResponse response = toResponse(trackerRepository.saveAndFlush(tracker), request);

        eventPublisher.publishEvent(new NexusFightStateSaved(nexusId, response));

        return response;
    }

    /**
     * Бой, который шевелился последним; пусто — мастер ещё ничего не вёл.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     */
    @Transactional(readOnly = true)
    public Optional<FightStateResponse> find(UUID userId, UUID nexusId) {
        requireAccess(userId, nexusId);

        return trackerRepository
                .findFirstByNexusIdAndStateIsNotNullOrderByStateUpdatedAtDesc(nexusId)
                .flatMap(tracker -> readState(tracker)
                        .map(state -> toResponse(tracker, state)));
    }

    /**
     * Разбирает снимок из хранилища.
     *
     * Снимок писала прежняя версия клиента — состав полей мог измениться, и
     * тогда карусель просто не показывается: ронять комнату из-за неё нечем.
     */
    private Optional<FightStateRequest> readState(NexusTracker tracker) {
        try {
            return Optional.of(objectMapper.readValue(tracker.getState(), FightStateRequest.class));
        } catch (JacksonException exception) {
            return Optional.empty();
        }
    }

    private void requireAccess(UUID userId, UUID nexusId) {
        if (!nexusService.hasAccess(nexusId, userId)) {
            // Чужая комната не должна отличаться от несуществующей.
            throw new NexusNotFoundException(nexusId);
        }
    }

    private static FightStateResponse toResponse(
            NexusTracker tracker,
            FightStateRequest state
    ) {
        List<FightStateRequest.FightParticipantRequest> participants =
                state.participants() == null ? List.of() : state.participants();

        return new FightStateResponse(
                tracker.getTrackerId(),
                tracker.getTitle(),
                state.round(),
                state.active(),
                state.currentParticipantId(),
                participants,
                tracker.getStateUpdatedAt());
    }
}
