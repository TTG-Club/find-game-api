package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameSystemRequest;
import club.ttg.findgame.game.api.GameSystemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class GameSystemService {

    private final GameSystemRepository repository;

    public GameSystemService(GameSystemRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public List<GameSystemResponse> findAll() {
        return repository.findAllByOrderByNameAsc().stream()
                .map(system -> new GameSystemResponse(system.getCode(), system.getName()))
                .toList();
    }

    @Transactional
    public GameSystemResponse create(CreateGameSystemRequest request) {
        // save() с заданным кодом пошёл бы в merge и молча переименовал
        // существующую систему: повторный код — это конфликт, а не правка.
        if (repository.existsById(request.code())) {
            throw new GameSystemAlreadyExistsException(request.code());
        }

        GameSystem saved = repository.save(new GameSystem(request.code(), request.name().strip()));
        return new GameSystemResponse(saved.getCode(), saved.getName());
    }
}
