package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameSystemRequest;
import club.ttg.findgame.game.api.GameSystemResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Game systems")
public class GameSystemController {

    private final GameSystemService service;

    public GameSystemController(GameSystemService service) {
        this.service = service;
    }

    @GetMapping("/game-systems")
    @Operation(summary = "Получить игровые системы")
    public List<GameSystemResponse> findAll() {
        return service.findAll();
    }

    @PostMapping("/moderation/game-systems")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Добавить игровую систему")
    @SecurityRequirement(name = "bearerAuth")
    public GameSystemResponse create(@Valid @RequestBody CreateGameSystemRequest request) {
        return service.create(request);
    }
}
