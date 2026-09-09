package club.ttg.findgame.finance;

import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/** Закрытые маршруты бухгалтерии: полномочия проверяются сервисом, а не вкладкой интерфейса. */
@RestController
@RequestMapping("/api/v1/games/{gameId}/finance")
public class GameFinanceController {
    private final GameFinanceService service;
    public GameFinanceController(GameFinanceService service) { this.service = service; }

    /** Бухгалтерия мастера. */
    @GetMapping
    public FinanceResponse all(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID gameId) {
        return service.read(UUID.fromString(jwt.getSubject()), gameId, false);
    }

    /** Личный счёт; идентификатор игрока нельзя подменить параметром запроса. */
    @GetMapping("/me")
    public FinanceResponse own(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID gameId) {
        return service.read(UUID.fromString(jwt.getSubject()), gameId, true);
    }

    /** Пополнение или корректировка владельцем игры. */
    @PostMapping("/players/{playerId}/entries")
    public void add(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID gameId, @PathVariable UUID playerId,
                    @Valid @RequestBody CreateFinanceEntryRequest request) {
        service.addEntry(UUID.fromString(jwt.getSubject()), gameId, playerId, request);
    }

    /** Оплата доступным авансом. */
    @PostMapping("/sessions/{sessionId}/balance")
    public void pay(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID gameId, @PathVariable UUID sessionId) {
        service.payFromBalance(UUID.fromString(jwt.getSubject()), gameId, sessionId);
    }

    /** Сообщение о внешней оплате. */
    @PostMapping("/sessions/{sessionId}/claim")
    public void claim(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID gameId, @PathVariable UUID sessionId) {
        service.claimPayment(UUID.fromString(jwt.getSubject()), gameId, sessionId);
    }

    /** Подтверждение или отклонение сообщения мастером. */
    @PostMapping("/sessions/{sessionId}/players/{playerId}/confirmation")
    public void confirm(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID gameId, @PathVariable UUID sessionId,
                        @PathVariable UUID playerId, @RequestParam boolean confirmed) {
        service.confirmPayment(UUID.fromString(jwt.getSubject()), gameId, sessionId, playerId, confirmed);
    }
}
