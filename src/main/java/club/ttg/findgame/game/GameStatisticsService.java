package club.ttg.findgame.game;

import club.ttg.findgame.game.api.GameStatisticsResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Общая статистика игр для панели администратора. */
@Service
@Transactional(readOnly = true)
public class GameStatisticsService {

    private final GameRepository repository;

    /** Принимает репозиторий игр. */
    public GameStatisticsService(GameRepository repository) {
        this.repository = repository;
    }

    /** Возвращает оба счётчика одним запросом, без загрузки самих игр. */
    public GameStatisticsResponse getStatistics() {
        return repository.getStatistics();
    }
}
