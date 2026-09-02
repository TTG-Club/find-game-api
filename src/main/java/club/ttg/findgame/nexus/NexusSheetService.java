package club.ttg.findgame.nexus;

import club.ttg.findgame.nexus.api.AddNexusSheetRequest;
import club.ttg.findgame.nexus.api.NexusSheetResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Листы персонажей, выложенные в комнату.
 *
 * Хранится только токен общего доступа: сам лист живёт в core-api, и комната
 * лишь показывает, чей персонаж за столом. Откуда игрок взял токен — свой лист
 * или сохранённый чужой, — сервис не различает: в токене этого нет, и решает
 * это тот, кто выкладывает.
 */
@Service
public class NexusSheetService {

    private final NexusCharacterSheetRepository sheetRepository;
    private final NexusRepository nexusRepository;
    private final NexusService nexusService;

    public NexusSheetService(
            NexusCharacterSheetRepository sheetRepository,
            NexusRepository nexusRepository,
            NexusService nexusService
    ) {
        this.sheetRepository = sheetRepository;
        this.nexusRepository = nexusRepository;
        this.nexusService = nexusService;
    }

    /**
     * Листы комнаты в порядке появления.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @return Листы вместе с правом убрать каждый.
     */
    @Transactional(readOnly = true)
    public List<NexusSheetResponse> findAll(UUID userId, UUID nexusId) {
        requireAccess(userId, nexusId);

        boolean owner = isRoomOwner(userId, nexusId);

        return sheetRepository.findAllByNexusIdOrderByCreatedAtAsc(nexusId).stream()
                .map(sheet -> toResponse(sheet, userId, owner))
                .toList();
    }

    /**
     * Выкладывает лист в комнату.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @param request Токен листа и подпись.
     * @return Выложенный лист.
     */
    @Transactional
    public NexusSheetResponse add(
            UUID userId,
            UUID nexusId,
            AddNexusSheetRequest request
    ) {
        requireAccess(userId, nexusId);

        String token = request.shareToken().strip();

        if (sheetRepository.existsByNexusIdAndShareToken(nexusId, token)) {
            throw new InvalidNexusException("Этот лист уже в комнате");
        }

        NexusCharacterSheet sheet = new NexusCharacterSheet();

        sheet.setNexusId(nexusId);
        sheet.setOwnerId(userId);
        sheet.setShareToken(token);
        sheet.setCharacterName(request.characterName().strip());

        try {
            return toResponse(
                    sheetRepository.saveAndFlush(sheet), userId, isRoomOwner(userId, nexusId));
        } catch (DataIntegrityViolationException exception) {
            // Двое выложили один и тот же лист одновременно — он уже в комнате.
            throw new InvalidNexusException("Этот лист уже в комнате");
        }
    }

    /**
     * Убирает лист из комнаты: свой — кто выложил, любой — владелец комнаты.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @param sheetId Лист.
     */
    @Transactional
    public void remove(UUID userId, UUID nexusId, UUID sheetId) {
        requireAccess(userId, nexusId);

        NexusCharacterSheet sheet = sheetRepository.findByIdAndNexusId(sheetId, nexusId)
                .orElseThrow(() -> new NexusNotFoundException("Лист не найден в комнате"));

        if (!sheet.getOwnerId().equals(userId) && !isRoomOwner(userId, nexusId)) {
            throw new NexusAccessDeniedException("Это чужой лист");
        }

        sheetRepository.delete(sheet);
    }

    /**
     * Передаёт лист другому участнику.
     *
     * Право у владельца комнаты: за столом он раздаёт персонажей — отдаёт
     * заготовленный лист игроку или забирает у ушедшего. Новый владелец
     * должен быть в комнате, иначе лист достался бы тому, кто его не увидит.
     *
     * @param userId Пользователь из токена.
     * @param nexusId Комната.
     * @param sheetId Лист.
     * @param newOwnerId Кому переходит лист.
     * @return Лист после передачи.
     */
    @Transactional
    public NexusSheetResponse transfer(
            UUID userId,
            UUID nexusId,
            UUID sheetId,
            UUID newOwnerId
    ) {
        requireAccess(userId, nexusId);

        if (!isRoomOwner(userId, nexusId)) {
            throw new NexusAccessDeniedException("Листы раздаёт владелец комнаты");
        }
        if (!nexusService.hasAccess(nexusId, newOwnerId)) {
            throw new InvalidNexusException("Этого игрока нет в комнате");
        }

        NexusCharacterSheet sheet = sheetRepository.findByIdAndNexusId(sheetId, nexusId)
                .orElseThrow(() -> new NexusNotFoundException("Лист не найден в комнате"));

        sheet.setOwnerId(newOwnerId);

        return toResponse(sheetRepository.save(sheet), userId, true);
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

    private static NexusSheetResponse toResponse(
            NexusCharacterSheet sheet,
            UUID userId,
            boolean roomOwner
    ) {
        boolean own = sheet.getOwnerId().equals(userId);

        return new NexusSheetResponse(
                sheet.getId(),
                sheet.getOwnerId(),
                // Токен — ключ к листу: с ним лист открывается и в обход
                // комнаты. Чужой лист смотрит только владелец комнаты, за
                // столом он ведёт игру и знает всех персонажей.
                roomOwner || own ? sheet.getShareToken() : null,
                sheet.getCharacterName(),
                roomOwner || own,
                sheet.getCreatedAt());
    }
}
