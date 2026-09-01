package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.GameResponse;
import club.ttg.findgame.game.api.GameSearchFilter;
import club.ttg.findgame.game.api.UpdateGameRequest;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.GameSeatCount;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.session.GameSessionStatus;
import club.ttg.findgame.subscription.SubscriptionStatusClient;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.time.Instant;
import java.time.Duration;

@Service
public class GameService {

    /**
     * Сколько игроков помещается за столом. Подписка расширяет предел: без неё
     * это обычная компания, с ней — большой стол или несколько групп.
     */
    private static final int FREE_MAX_PLAYERS = 5;
    private static final int SUBSCRIBER_MAX_PLAYERS = 15;

    private static final Duration FREE_RAISE_INTERVAL = Duration.ofDays(1);
    private static final Duration SUBSCRIBER_RAISE_INTERVAL = Duration.ofHours(1);

    private final GameRepository repository;
    private final GameMapper mapper;
    private final SubscriptionStatusClient subscriptionStatusClient;
    private final GameCreationLockService creationLockService;
    // Нужны редактированию: правка не должна расходиться с уже созданными
    // сессиями и принятыми в них игроками.
    private final GameSessionRepository sessionRepository;
    private final GameRegistrationRepository registrationRepository;

    public GameService(
            GameRepository repository,
            GameMapper mapper,
            SubscriptionStatusClient subscriptionStatusClient,
            GameCreationLockService creationLockService,
            GameSessionRepository sessionRepository,
            GameRegistrationRepository registrationRepository
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
        enforceMaxPlayersLimit(username, request.maxPlayers());
        enforceActiveGameLimit(masterId, username);

        Game game = mapper.toEntity(request);
        game.setMasterId(masterId);
        game.setStatus(GameStatus.OPEN);
        if (game.getVisibility() == GameVisibility.PRIVATE) {
            game.setInviteCode(UUID.randomUUID());
        }
        return toOwnerResponse(repository.save(game));
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
    public GameResponse update(
            UUID masterId,
            String username,
            UUID gameId,
            UpdateGameRequest request
    ) {
        Game game = repository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (!game.getMasterId().equals(masterId)) {
            throw new GameAccessDeniedException();
        }
        if (request.playersToStart() > request.maxPlayers()) {
            throw new InvalidPlayerCountException();
        }
        validateDetails(request.type(), request.city(), request.minAge(), request.maxAge());
        enforceMaxPlayersLimit(username, request.maxPlayers());
        validateCostTypeChange(game, request.costType());
        validateMaxPlayersChange(gameId, request.maxPlayers());

        GameVisibility previousVisibility = game.getVisibility();
        mapper.updateEntity(game, request);
        applyVisibilityChange(game, previousVisibility);

        return toOwnerResponse(repository.save(game));
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
     * Максимум игроков не опускается ниже уже принятых в игру: сервис не даёт
     * принять игроков сверх лимита, и созданный правкой перебор чинить было бы
     * нечем.
     */
    private void validateMaxPlayersChange(UUID gameId, int requestedMaxPlayers) {
        long approved = registrationRepository.countByGameIdAndStatus(
                gameId, RegistrationStatus.APPROVED);
        if (approved > requestedMaxPlayers) {
            throw new InvalidPlayerCountException(
                    "В игру уже принято %d игроков — максимум не может быть меньше".formatted(approved));
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
        finish(masterId, gameId, GameStatus.CLOSED);
    }

    /**
     * Отменяет игру: она не состоялась. Отдельный исход, а не разновидность
     * завершения — по закрытым играм мастера видно, что было сыграно, и
     * несостоявшимся среди них не место.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     */
    @Transactional
    public void cancel(UUID masterId, UUID gameId) {
        finish(masterId, gameId, GameStatus.CANCELLED);
    }

    /** Переводит игру в конечное состояние: сыграна или не состоялась. */
    private void finish(UUID masterId, UUID gameId, GameStatus status) {
        Game game = repository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        if (!game.getMasterId().equals(masterId)) {
            throw new GameAccessDeniedException();
        }
        game.setStatus(status);
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
        Page<Game> games = repository.findAll(GameSpecifications.publicGames(filter), pageable);
        Map<UUID, Seats> seats = countTakenSeats(games.getContent());
        return games.map(game -> toPublicResponse(game, seats));
    }

    /**
     * Игры пользователя: свои как мастер и те, куда он подал заявку или
     * принят игроком. Публичный поиск эту выдачу не заменяет — приватные игры
     * в него не попадают, а закрытые всё равно нужно видеть.
     *
     * Код приглашения уходит только владельцу: игроку чужой приватной игры он
     * дал бы право звать в неё кого угодно.
     */
    @Transactional(readOnly = true)
    public Page<GameResponse> findOwn(UUID userId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, listOrder());
        Page<Game> games = repository.findAllOwnOrJoined(userId, pageable);
        Map<UUID, Seats> seats = countTakenSeats(games.getContent());

        return games.map(game -> game.getMasterId().equals(userId)
                ? toResponse(game, seats)
                : toPublicResponse(game, seats));
    }

    /**
     * Страница игры.
     *
     * Владелец открывает свою игру всегда: приватную он и создал, кода
     * приглашения у себя в адресной строке у него нет, и требовать его от
     * автора — значит запирать мастера снаружи собственной игры. Ему же
     * уходит код приглашения: без него ссылку для игроков не собрать.
     *
     * @param requesterId Пользователь из токена; {@code null} — аноним.
     * @param gameId Игра.
     * @param inviteCode Код приглашения из адреса страницы.
     * @return Игра глазами запросившего.
     */
    @Transactional(readOnly = true)
    public GameResponse get(UUID requesterId, UUID gameId, UUID inviteCode) {
        Game game = repository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (requesterId != null && game.getMasterId().equals(requesterId)) {
            return toOwnerResponse(game);
        }

        boolean visible = game.getVisibility() == GameVisibility.PUBLIC
                || (inviteCode != null && inviteCode.equals(game.getInviteCode()));

        if (!visible) {
            throw new GameNotFoundException(gameId);
        }

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
        return toPublicResponse(game, countTakenSeats(List.of(game)));
    }

    private GameResponse toPublicResponse(Game game, Map<UUID, Seats> seats) {
        return toResponse(game, seats).copyWithoutInviteCode();
    }

    /**
     * Ответ владельцу: с кодом приглашения — он нужен мастеру, чтобы собрать
     * ссылку на приватную игру.
     */
    private GameResponse toOwnerResponse(Game game) {
        return toResponse(game, countTakenSeats(List.of(game)));
    }

    private static Seats seatsOf(Map<UUID, Seats> seats, Game game) {
        // Идентификатор игре присваивается при сохранении, так что до него
        // считать нечего — и искать по пустому ключу тоже.
        return game.getId() == null ? Seats.EMPTY : seats.getOrDefault(game.getId(), Seats.EMPTY);
    }

    private GameResponse toResponse(Game game, Map<UUID, Seats> seats) {
        Seats gameSeats = seatsOf(seats, game);

        return mapper.toResponse(game, gameSeats.taken(), gameSeats.approved());
    }

    /**
     * Сколько мест занято в каждой игре. Игрок записывается в игру целиком,
     * поэтому занятость считается по её заявкам — одним запросом на всю
     * страницу выдачи.
     */
    private Map<UUID, Seats> countTakenSeats(Collection<Game> games) {
        List<UUID> gameIds = games.stream().map(Game::getId).filter(Objects::nonNull).toList();
        if (gameIds.isEmpty()) {
            return Map.of();
        }

        return registrationRepository
                .countTakenSeatsByGame(
                        gameIds, RegistrationStatus.REJECTED, RegistrationStatus.APPROVED)
                .stream()
                .collect(Collectors.toMap(
                        GameSeatCount::getGameId,
                        count -> new Seats(
                                Math.toIntExact(count.getPlayerCount()),
                                Math.toIntExact(count.getApprovedCount()))));
    }

    /** Занятость мест ближайшей сессии: всего занято и из них подтверждено. */
    private record Seats(int taken, int approved) {

        private static final Seats EMPTY = new Seats(0, 0);
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

    /**
     * Предел стола: без подписки за ним помещается меньше игроков.
     *
     * Проверяется и при создании, и при правке — иначе игру заводили бы на
     * пятерых, а сразу после сохранения расширяли до пятнадцати.
     */
    private void enforceMaxPlayersLimit(String username, int maxPlayers) {
        int limit = hasActiveSubscription(username)
                ? SUBSCRIBER_MAX_PLAYERS
                : FREE_MAX_PLAYERS;

        if (maxPlayers > limit) {
            throw new InvalidPlayerCountException(
                    "Больше %d игроков в игре не бывает".formatted(limit));
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
