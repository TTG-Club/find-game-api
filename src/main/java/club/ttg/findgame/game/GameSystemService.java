package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameSystemRequest;
import club.ttg.findgame.game.api.GameSystemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Stream;

@Service
public class GameSystemService {

    private final GameSystemRepository repository;

    public GameSystemService(GameSystemRepository repository) {
        this.repository = repository;
    }

    /**
     * Системы по названию, своя система — последней: это выход для тех, кто не
     * нашёл свою в списке, и в начале списка она мешала бы поиску.
     */
    @Transactional(readOnly = true)
    public List<GameSystemResponse> findAll() {
        List<GameSystem> systems = repository.findAllByOrderByNameAsc();

        return Stream.concat(
                        systems.stream().filter(system -> !isHomebrew(system)),
                        systems.stream().filter(GameSystemService::isHomebrew))
                .map(system -> new GameSystemResponse(system.getCode(), system.getName()))
                .toList();
    }

    private static boolean isHomebrew(GameSystem system) {
        return GameSystem.HOMEBREW.equals(system.getCode());
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
