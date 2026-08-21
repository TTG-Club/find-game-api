package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.GameResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.Instant;

@Service
public class GameService {

    private final GameRepository repository;
    private final GameMapper mapper;

    public GameService(GameRepository repository, GameMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Transactional
    public GameResponse create(UUID masterId, CreateGameRequest request) {
        if (request.playersToStart() > request.maxPlayers()) {
            throw new InvalidPlayerCountException();
        }
        validateDetails(request);

        Game game = mapper.toEntity(request);
        game.setMasterId(masterId);
        game.setStatus(GameStatus.OPEN);
        if (game.getVisibility() == GameVisibility.PRIVATE) {
            game.setInviteCode(UUID.randomUUID());
        }
        return mapper.toResponse(repository.save(game));
    }

    @Transactional(readOnly = true)
    public Page<GameResponse> findPublic(GameSystem system, GameType type, int page, int size) {
        Specification<Game> filter = (root, query, cb) -> cb.and(
                cb.equal(root.get("visibility"), GameVisibility.PUBLIC),
                cb.isNull(root.get("deletedAt"))
        );
        if (system != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("system"), system));
        }
        if (type != null) {
            filter = filter.and((root, query, cb) -> cb.equal(root.get("type"), type));
        }

        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt")
                .and(Sort.by(Sort.Direction.DESC, "id"));
        PageRequest pageable = PageRequest.of(page, size, sort);
        return repository.findAll(filter, pageable).map(this::toPublicResponse);
    }

    @Transactional(readOnly = true)
    public GameResponse get(UUID gameId, UUID inviteCode) {
        Game game = inviteCode == null
                ? repository.findByIdAndVisibilityAndDeletedAtIsNull(gameId, GameVisibility.PUBLIC)
                    .orElseThrow(() -> new GameNotFoundException(gameId))
                : repository.findByIdAndInviteCodeAndDeletedAtIsNull(gameId, inviteCode)
                    .orElseThrow(() -> new GameNotFoundException(gameId));
        return toPublicResponse(game);
    }

    @Transactional
    public void delete(UUID gameId, String reason) {
        Game game = repository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        game.setDeletedAt(Instant.now());
        game.setDeletionReason(reason == null ? null : reason.trim());
        repository.save(game);
    }

    private GameResponse toPublicResponse(Game game) {
        GameResponse response = mapper.toResponse(game);
        return new GameResponse(
                response.id(), response.masterId(), response.title(), response.system(), response.imageUrl(),
                response.virtualTableUrl(), response.genre(), response.description(), response.requirements(),
                response.allowedSources(), response.type(), response.city(), response.playersToStart(),
                response.maxPlayers(), response.minAge(), response.maxAge(), response.startingLevel(),
                response.crossplayAllowed(), response.status(), response.durationType(), response.costType(),
                response.visibility(), null,
                response.createdAt(), response.updatedAt());
    }

    private void validateDetails(CreateGameRequest request) {
        if (request.type() == GameType.ONLINE && request.city() != null) {
            throw new InvalidGameDetailsException("Город можно указывать только для офлайн-игры");
        }
        if (request.city() != null && request.city().isBlank()) {
            throw new InvalidGameDetailsException("Город не может быть пустой строкой");
        }
        if (request.minAge() != null && request.maxAge() != null
                && request.minAge() > request.maxAge()) {
            throw new InvalidGameDetailsException("Минимальный возраст не может превышать максимальный");
        }
    }

}
