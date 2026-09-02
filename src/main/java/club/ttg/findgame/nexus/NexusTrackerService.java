package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.AddNexusTrackerRequest;
import club.ttg.findgame.nexus.api.NexusTrackerResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Трекеры инициативы комнаты.
 *
 * Бой ведётся в core-api: там бестиарий, листы и сам порядок ходов. Комната
 * хранит только ссылку на трекер — чтобы группа находила свой бой, не роясь в
 * общем списке.
 */
@Service
public class NexusTrackerService {

    private final NexusTrackerRepository trackerRepository;
    private final NexusRepository nexusRepository;
    private final NexusService nexusService;

    public NexusTrackerService(
            NexusTrackerRepository trackerRepository,
            NexusRepository nexusRepository,
            NexusService nexusService
    ) {
        this.trackerRepository = trackerRepository;
        this.nexusRepository = nexusRepository;
        this.nexusService = nexusService;
    }

    /**
     * Трекеры комнаты, свежие первыми.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     */
    @Transactional(readOnly = true)
    public List<NexusTrackerResponse> findAll(UUID userId, UUID nexusId) {
        requireAccess(userId, nexusId);

        boolean owner = isRoomOwner(userId, nexusId);

        return trackerRepository.findAllByNexusIdOrderByCreatedAtDesc(nexusId).stream()
                .map(tracker -> toResponse(tracker, owner))
                .toList();
    }

    /**
     * Заводит трекер в комнате.
     *
     * Право у владельца комнаты: бой ставит тот, кто ведёт игру, — иначе у
     * группы появилось бы столько боёв, сколько в ней людей.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @param request Трекер и его название.
     * @return Заведённый трекер.
     */
    @Transactional
    public NexusTrackerResponse add(
            UUID userId,
            UUID nexusId,
            AddNexusTrackerRequest request
    ) {
        requireAccess(userId, nexusId);

        if (!isRoomOwner(userId, nexusId)) {
            throw new NexusAccessDeniedException("Бой ставит владелец комнаты");
        }
        if (trackerRepository.existsByNexusIdAndTrackerId(nexusId, request.trackerId())) {
            throw new InvalidNexusException("Этот трекер уже в комнате");
        }

        NexusTracker tracker = new NexusTracker();

        tracker.setNexusId(nexusId);
        tracker.setTrackerId(request.trackerId());
        tracker.setTitle(request.title().strip());
        tracker.setCreatedBy(userId);

        try {
            return toResponse(trackerRepository.saveAndFlush(tracker), true);
        } catch (DataIntegrityViolationException exception) {
            throw new InvalidNexusException("Этот трекер уже в комнате");
        }
    }

    /**
     * Убирает трекер из комнаты. Сам трекер остаётся в core-api: комната лишь
     * перестаёт на него ссылаться.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @param id Запись трекера в комнате.
     */
    @Transactional
    public void remove(UUID userId, UUID nexusId, UUID id) {
        requireAccess(userId, nexusId);

        if (!isRoomOwner(userId, nexusId)) {
            throw new NexusAccessDeniedException("Бой убирает владелец комнаты");
        }

        NexusTracker tracker = trackerRepository.findByIdAndNexusId(id, nexusId)
                .orElseThrow(() -> new NexusNotFoundException("Трекер не найден в комнате"));

        trackerRepository.delete(tracker);
    }

    private boolean isRoomOwner(UUID userId, UUID nexusId) {
        return nexusRepository.findById(nexusId)
                .map(nexus -> nexus.getOwnerId().equals(userId))
                .orElse(false);
    }

    private void requireAccess(UUID userId, UUID nexusId) {
        if (!nexusService.hasAccess(nexusId, userId)) {
            // Чужая комната не должна отличаться от несуществующей.
            throw new NexusNotFoundException(nexusId);
        }
    }

    private static NexusTrackerResponse toResponse(
            NexusTracker tracker,
            boolean roomOwner
    ) {
        return new NexusTrackerResponse(
                tracker.getId(),
                tracker.getTrackerId(),
                tracker.getTitle(),
                tracker.getCreatedBy(),
                roomOwner,
                tracker.getCreatedAt());
    }
}
