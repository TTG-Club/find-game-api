package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.GameResponse;
import club.ttg.findgame.game.api.GameSearchFilter;
import club.ttg.findgame.subscription.SubscriptionStatusClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.time.Instant;
import java.time.Duration;

@Service
public class GameService {

    private static final Duration FREE_RAISE_INTERVAL = Duration.ofDays(1);
    private static final Duration SUBSCRIBER_RAISE_INTERVAL = Duration.ofHours(1);

    private final GameRepository repository;
    private final GameMapper mapper;
    private final SubscriptionStatusClient subscriptionStatusClient;
    private final GameCreationLockService creationLockService;

    public GameService(
            GameRepository repository,
            GameMapper mapper,
            SubscriptionStatusClient subscriptionStatusClient,
            GameCreationLockService creationLockService
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.subscriptionStatusClient = subscriptionStatusClient;
        this.creationLockService = creationLockService;
    }

    @Transactional
    public GameResponse create(UUID masterId, String username, CreateGameRequest request) {
        if (request.playersToStart() > request.maxPlayers()) {
            throw new InvalidPlayerCountException();
        }
        validateDetails(request);
        enforceActiveGameLimit(masterId, username);

        Game game = mapper.toEntity(request);
        game.setMasterId(masterId);
        game.setStatus(GameStatus.OPEN);
        if (game.getVisibility() == GameVisibility.PRIVATE) {
            game.setInviteCode(UUID.randomUUID());
        }
        return mapper.toResponse(repository.save(game));
    }

    @Transactional
    public void close(UUID masterId, UUID gameId) {
        Game game = repository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (!game.getMasterId().equals(masterId)) {
            throw new GameAccessDeniedException();
        }
        game.setStatus(GameStatus.CLOSED);
        repository.save(game);
    }

    @Transactional
    public GameResponse raise(UUID masterId, String username, UUID gameId) {
        boolean subscriptionActive = hasActiveSubscription(username);
        Game game = repository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (!game.getMasterId().equals(masterId)) {
            throw new GameAccessDeniedException();
        }
        if (game.getVisibility() != GameVisibility.PUBLIC || game.getStatus() != GameStatus.OPEN) {
            throw new GameCannotBeRaisedException();
        }

        Duration interval = subscriptionActive ? SUBSCRIBER_RAISE_INTERVAL : FREE_RAISE_INTERVAL;
        Instant now = Instant.now();
        Instant availableAt = game.getListPositionAt().plus(interval);
        if (availableAt.isAfter(now)) {
            throw new GameRaiseCooldownException(availableAt);
        }

        game.setListPositionAt(now);
        return toPublicResponse(repository.save(game));
    }

    @Transactional(readOnly = true)
    public Page<GameResponse> findPublic(GameSearchFilter filter, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, listOrder());
        return repository.findAll(GameSpecifications.publicGames(filter), pageable).map(this::toPublicResponse);
    }

    /**
     * Игры мастера-владельца: и публичные, и приватные, в любом статусе.
     * Публичный поиск заменить эту выдачу не может — приватные игры в него не
     * попадают, а закрытые мастеру всё равно нужно видеть.
     *
     * В отличие от публичных ответов {@code inviteCode} здесь не вырезается:
     * без него владелец не соберёт ссылку-приглашение на свою приватную игру.
     */
    @Transactional(readOnly = true)
    public Page<GameResponse> findOwn(UUID masterId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, listOrder());
        return repository.findAllByMasterIdAndDeletedAtIsNull(masterId, pageable).map(mapper::toResponse);
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

    /**
     * Порядок списков игр: сначала поднятые и свежие, затем по {@code id} —
     * второй ключ делает страницы стабильными при равных {@code listPositionAt}.
     */
    private static Sort listOrder() {
        return Sort.by(Sort.Direction.DESC, "listPositionAt")
                .and(Sort.by(Sort.Direction.DESC, "id"));
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
                response.createdAt(), response.listPositionAt(), response.updatedAt());
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

    private void enforceActiveGameLimit(UUID masterId, String username) {
        if (hasActiveSubscription(username)) {
            return;
        }

        creationLockService.lock(masterId);
        if (repository.existsByMasterIdAndStatusNotAndDeletedAtIsNull(masterId, GameStatus.CLOSED)) {
            throw new ActiveGameLimitExceededException();
        }
    }

    private boolean hasActiveSubscription(String username) {
        return subscriptionStatusClient.status(username)
                .map(SubscriptionStatusClient.SubscriptionStatus::active)
                .orElse(false);
    }

}
