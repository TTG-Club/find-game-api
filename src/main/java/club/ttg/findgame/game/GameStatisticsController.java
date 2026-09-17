package club.ttg.findgame.game;

import club.ttg.findgame.game.api.GameStatisticsResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Статистика игр; доступ ADMIN проверяет SecurityConfiguration. */
@RestController
@RequestMapping("/api/v1/admin/statistics/games")
@SecurityRequirement(name = "bearerAuth")
public class GameStatisticsController {

    private final GameStatisticsService service;

    /** Принимает сервис статистики. */
    public GameStatisticsController(GameStatisticsService service) {
        this.service = service;
    }

    /** Возвращает общее число игр и число завершённых. */
    @GetMapping
    @Operation(summary = "Статистика игр для администратора")
    public GameStatisticsResponse getStatistics() {
        return service.getStatistics();
    }
}
