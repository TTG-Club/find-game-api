package club.ttg.findgame.game;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
class GameCreationLockService {

    private final GameCreationLockRepository repository;

    GameCreationLockService(GameCreationLockRepository repository) {
        this.repository = repository;
    }

    void lock(UUID masterId) {
        repository.ensureExists(masterId);
        repository.findByMasterIdForUpdate(masterId)
                .orElseThrow(() -> new IllegalStateException("Не удалось заблокировать создание игры"));
    }
}
