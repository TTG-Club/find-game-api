package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.GameResponse;
import club.ttg.findgame.game.api.GameSearchFilter;
import club.ttg.findgame.game.api.UpdateGameRequest;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistrationStatus;
import club.ttg.findgame.session.GameSessionRepository;
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
    // Нужны редактированию: правка не должна расходиться с уже созданными
    // сессиями и принятыми в них игроками.
    private final GameSessionRepository sessionRepository;
    private final SessionRegistrationRepository registrationRepository;

    public GameService(
            GameRepository repository,
            GameMapper mapper,
            SubscriptionStatusClient subscriptionStatusClient,
            GameCreationLockService creationLockService,
            GameSessionRepository sessionRepository,
            SessionRegistrationRepository registrationRepository
    ) {
        this.repository = repository;
        this.mapper = mapper;
        this.subscriptionStatusClient = subscriptionStatusClient;
        this.creationLockService = creationLockService;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
    }

    @Transactional
    public GameResponse create(UUID masterId, String username, CreateGameRequest request) {
        if (request.playersToStart() > request.maxPlayers()) {
            throw new InvalidPlayerCountException();
        }
        validateDetails(request.type(), request.city(), request.minAge(), request.maxAge());
        enforceActiveGameLimit(masterId, username);

        Game game = mapper.toEntity(request);
        game.setMasterId(masterId);
        game.setStatus(GameStatus.OPEN);
        if (game.getVisibility() == GameVisibility.PRIVATE) {
            game.setInviteCode(UUID.randomUUID());
        }
        return mapper.toResponse(repository.save(game));
    }

    /**
     * Изменяет свою игру. Правки принимаются целиком: форма редактирования —
     * та же, что и создания, поэтому и проверки те же.
     *
     * Сверх них два ограничения, которых нет при создании, — оба защищают уже
     * существующие сессии и заявки от рассинхронизации:
     * <ul>
     *   <li>платность нельзя переключить, когда у игры уже есть сессии: у
     *   сессий бесплатной игры нет ни суммы, ни условий оплаты, а у платной
     *   они обязательны, и задним числом это не выправить;</li>
     *   <li>максимум игроков нельзя опустить ниже числа уже принятых в
     *   какую-либо сессию — иначе принятые окажутся сверх лимита.</li>
     * </ul>
     *
     * Смена видимости управляет кодом приглашения: он выдаётся при переходе в
     * приватную игру и снимается при возврате в публичную.
     */
    @Transactional
    public GameResponse update(UUID masterId, UUID gameId, UpdateGameRequest request) {
        Game game = repository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (!game.getMasterId().equals(masterId)) {
            throw new GameAccessDeniedException();
        }
        if (request.playersToStart() > request.maxPlayers()) {
            throw new InvalidPlayerCountException();
        }
        validateDetails(request.type(), request.city(), request.minAge(), request.maxAge());
        validateCostTypeChange(game, request.costType());
        validateMaxPlayersChange(gameId, request.maxPlayers());

        GameVisibility previousVisibility = game.getVisibility();
        mapper.updateEntity(game, request);
        applyVisibilityChange(game, previousVisibility);

        return mapper.toResponse(repository.save(game));
    }

    /**
     * Платность меняется только у игры без сессий: у существующих сессий
     * платёжные поля уже зафиксированы под прежний тип.
     */
    private void validateCostTypeChange(Game game, GameCostType requested) {
        if (game.getCostType() == requested) {
            return;
        }
        if (sessionRepository.existsByGameId(game.getId())) {
            throw new InvalidGameDetailsException(
                    "Платность нельзя изменить, когда у игры уже есть сессии");
        }
    }

    /**
     * Максимум игроков не опускается ниже уже принятых: сервис не даёт принять
     * игроков сверх лимита, и созданный правкой перебор чинить было бы нечем.
     */
    private void validateMaxPlayersChange(UUID gameId, int requestedMaxPlayers) {
        long approved = sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId).stream()
                .mapToLong(session -> registrationRepository.countBySessionIdAndStatus(
                        session.getId(), SessionRegistrationStatus.APPROVED))
                .max()
                .orElse(0L);
        if (approved > requestedMaxPlayers) {
            throw new InvalidPlayerCountException(
                    "В сессию уже принято %d игроков — максимум не может быть меньше".formatted(approved));
        }
    }

    /**
     * Держит код приглашения в согласии с видимостью: приватной игре он нужен,
     * публичной — нет, и оставленный код открывал бы прямой доступ и дальше.
     */
    private void applyVisibilityChange(Game game, GameVisibility previousVisibility) {
        if (game.getVisibility() == previousVisibility) {
            return;
        }
        if (game.getVisibility() == GameVisibility.PRIVATE) {
            game.setInviteCode(UUID.randomUUID());
        } else {
            game.setInviteCode(null);
        }
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

    /**
     * Проверки, общие для создания и редактирования: город только у офлайна и
     * непротиворечивые возрастные границы.
     */
    private void validateDetails(GameType type, String city, Integer minAge, Integer maxAge) {
        if (type == GameType.ONLINE && city != null) {
            throw new InvalidGameDetailsException("Город можно указывать только для офлайн-игры");
        }
        if (city != null && city.isBlank()) {
            throw new InvalidGameDetailsException("Город не может быть пустой строкой");
        }
        if (minAge != null && maxAge != null && minAge > maxAge) {
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
