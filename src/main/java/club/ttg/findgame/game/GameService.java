package club.ttg.findgame.game;

import club.ttg.findgame.account.AuthAccountClient;
import club.ttg.findgame.account.UnverifiedEmailException;
import club.ttg.findgame.follow.FollowService;
import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import club.ttg.findgame.game.api.GameResponse;
import club.ttg.findgame.game.api.NextGameSessionResponse;
import club.ttg.findgame.registration.GameRegistration;
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
import org.springframework.data.domain.Limit;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

    /**
     * Сколько раз за сутки игру можно поднять в списке. Подписка даёт больше
     * попыток, но не безлимит: иначе верх списка занял бы один мастер.
     */
    private static final int FREE_RAISES_PER_DAY = 1;
    private static final int SUBSCRIBER_RAISES_PER_DAY = 3;

    /** Окно, в котором считаются поднятия. */
    private static final Duration RAISE_WINDOW = Duration.ofDays(1);

    private final GameRepository repository;
    private final GameRaiseRepository raiseRepository;
    private final GameMapper mapper;
    private final SubscriptionStatusClient subscriptionStatusClient;
    private final GameCreationLockService creationLockService;
    // Объявление видят все, поэтому заводить его вправе только владелец
    // подтверждённого адреса — иначе игру создаст любая одноразовая почта.
    private final AuthAccountClient authAccountClient;
    // Нужны редактированию: правка не должна расходиться с уже созданными
    // сессиями и принятыми в них игроками.
    private final GameSessionRepository sessionRepository;
    private final GameRegistrationRepository registrationRepository;
    // Новую игру ждут те, кто отметил мастера: без рассылки отметка бесполезна.
    private final FollowService followService;
    // Об исходе игры её состав узнаёт уведомлением.
    private final NotificationService notificationService;

    public GameService(
            GameRepository repository,
            GameRaiseRepository raiseRepository,
            GameMapper mapper,
            SubscriptionStatusClient subscriptionStatusClient,
            GameCreationLockService creationLockService,
            AuthAccountClient authAccountClient,
            GameSessionRepository sessionRepository,
            GameRegistrationRepository registrationRepository,
            FollowService followService,
            NotificationService notificationService
    ) {
        this.repository = repository;
        this.raiseRepository = raiseRepository;
        this.mapper = mapper;
        this.subscriptionStatusClient = subscriptionStatusClient;
        this.creationLockService = creationLockService;
        this.authAccountClient = authAccountClient;
        this.sessionRepository = sessionRepository;
        this.registrationRepository = registrationRepository;
        this.followService = followService;
        this.notificationService = notificationService;
    }

    @Transactional
    public GameResponse create(UUID masterId, String username, String accessToken, CreateGameRequest request) {
        requireVerifiedEmail(accessToken);
        if (request.playersToStart() > request.maxPlayers()) {
            throw new InvalidPlayerCountException();
        }
        validateDetails(request.type(), request.city(), request.venue(), request.minAge(), request.maxAge());
        enforceMaxPlayersLimit(username, request.maxPlayers());
        enforceActiveGameLimit(masterId, username);

        Game game = mapper.toEntity(request);
        game.setOnlinePlatform(resolveOnlinePlatform(request.type(), request.onlinePlatform(), null));
        game.setMasterId(masterId);
        game.setStatus(GameStatus.OPEN);
        if (game.getVisibility() == GameVisibility.PRIVATE) {
            game.setInviteCode(UUID.randomUUID());
        }
        Game saved = repository.save(game);

        followService.announceGame(saved);

        return toOwnerResponse(saved);
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
        validateDetails(request.type(), request.city(), request.venue(), request.minAge(), request.maxAge());
        enforceMaxPlayersLimit(username, request.maxPlayers());
        validateCostTypeChange(game, request.costType());
        validatePlayerCountChange(gameId, request.playersToStart(), request.maxPlayers());

        GameVisibility previousVisibility = game.getVisibility();
        mapper.updateEntity(game, request);
        game.setOnlinePlatform(resolveOnlinePlatform(request.type(), request.onlinePlatform(), game.getOnlinePlatform()));
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

    /** Сохраняет выбор старых клиентов, задаёт значение по умолчанию и очищает поле вне онлайн-игр. */
    private static GameOnlinePlatform resolveOnlinePlatform(
            GameType type, GameOnlinePlatform requested, GameOnlinePlatform current) {
        if (type != GameType.ONLINE) {
            return null;
        }
        if (requested != null) {
            return requested;
        }
        return current != null ? current : GameOnlinePlatform.VTTG;
    }

    /**
     * Состав не ужимается ниже уже принятых в игру. Максимум — потому что
     * сервис не даёт принять игроков сверх него, и созданный правкой перебор
     * чинить было бы нечем. Минимум — потому что порог старта, который уже
     * пройден, перестал бы что-либо значить.
     */
    private void validatePlayerCountChange(
            UUID gameId,
            int requestedPlayersToStart,
            int requestedMaxPlayers
    ) {
        long approved = registrationRepository.countByGameIdAndStatus(
                gameId, RegistrationStatus.APPROVED);

        if (approved > requestedMaxPlayers) {
            throw new InvalidPlayerCountException(
                    "В игру уже принято %d игроков — максимум не может быть меньше".formatted(approved));
        }

        if (approved > requestedPlayersToStart) {
            throw new InvalidPlayerCountException(
                    "В игру уже принято %d игроков — минимум не может быть меньше".formatted(approved));
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

    /**
     * Закрывает набор досрочно: мастеру хватает тех, кого он уже принял.
     * Совсем пустую игру закрывать не дают — объявление без единого игрока
     * просто исчезло бы из поиска, ничего не собрав.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @return Игра с закрытым набором.
     */
    @Transactional
    public GameResponse closeRecruitment(UUID masterId, UUID gameId) {
        Game game = ownGameForUpdate(masterId, gameId);
        long approved = registrationRepository.countByGameIdAndStatus(
                gameId, RegistrationStatus.APPROVED);

        if (approved < 1) {
            throw new InvalidGameDetailsException(
                    "Набор закрывают, когда принят хотя бы один игрок");
        }

        game.setRecruitmentClosed(true);

        return toOwnerResponse(repository.save(game));
    }

    /**
     * Открывает набор снова. За полный стол это не работает: свободного места
     * там нет, и объявление звало бы впустую.
     *
     * @param masterId Владелец игры из токена.
     * @param gameId Игра.
     * @return Игра с открытым набором.
     */
    @Transactional
    public GameResponse openRecruitment(UUID masterId, UUID gameId) {
        Game game = ownGameForUpdate(masterId, gameId);
        long taken = registrationRepository.countByGameIdAndStatusNot(
                gameId, RegistrationStatus.REJECTED);

        if (taken >= game.getMaxPlayers()) {
            throw new InvalidGameDetailsException(
                    "Свободных мест нет — набор открывать некуда");
        }

        game.setRecruitmentClosed(false);

        return toOwnerResponse(repository.save(game));
    }

    /** Своя игра под правку: чужую мастер не трогает. */
    private Game ownGameForUpdate(UUID masterId, UUID gameId) {
        Game game = repository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (!game.getMasterId().equals(masterId)) {
            throw new GameAccessDeniedException();
        }

        return game;
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

        // Об исходе игры состав узнаёт только отсюда: сессии при её закрытии
        // не трогаются, и в расписании игрока ничего не меняется.
        notificationService.notifyUsers(
                approvedPlayerIds(gameId), masterId, finishNotification(status),
                game.getId(), game.getTitle(), null, null);
    }

    /** Отменённая игра и сыгранная — разные новости для игрока. */
    private static NotificationType finishNotification(GameStatus status) {
        return status == GameStatus.CANCELLED
                ? NotificationType.GAME_CANCELLED
                : NotificationType.GAME_CLOSED;
    }

    /** Принятые в игру: им адресованы новости об игре целиком. */
    private List<UUID> approvedPlayerIds(UUID gameId) {
        return registrationRepository
                .findAllByGameIdAndStatus(gameId, RegistrationStatus.APPROVED).stream()
                .map(GameRegistration::getPlayerId)
                .toList();
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

        int limit = subscriptionActive ? SUBSCRIBER_RAISES_PER_DAY : FREE_RAISES_PER_DAY;
        Instant now = Instant.now();
        Instant since = now.minus(RAISE_WINDOW);
        long used = raiseRepository.countByGameIdAndRaisedAtAfter(gameId, since);

        if (used >= limit) {
            // Норма освободится, когда из окна выйдет самое раннее поднятие.
            throw new GameRaiseCooldownException(nextRaiseAt(gameId, since));
        }

        GameRaise raise = new GameRaise();

        raise.setGameId(gameId);
        raise.setRaisedAt(now);
        raiseRepository.save(raise);

        game.setListPositionAt(now);

        return toPublicResponse(repository.save(game));
    }

    /**
     * Когда освободится место в суточной норме: самое раннее поднятие окна
     * плюс само окно.
     *
     * @param gameId Игра.
     * @param since Начало окна.
     */
    private Instant nextRaiseAt(UUID gameId, Instant since) {
        return raiseRepository.findWindow(gameId, since, Limit.of(1)).stream()
                .findFirst()
                .map(raise -> raise.getRaisedAt().plus(RAISE_WINDOW))
                .orElse(since.plus(RAISE_WINDOW));
    }

    /**
     * Публичная выдача каталога.
     *
     * @param filter Условия отбора.
     * @param page Номер страницы с нуля.
     * @param size Размер страницы.
     * @param viewerId Кто смотрит; {@code null} — гость. Нужен отбору по
     * избранному: список отметок личный.
     */
    @Transactional(readOnly = true)
    public Page<GameResponse> findPublic(GameSearchFilter filter, int page, int size, UUID viewerId) {
        PageRequest pageable = PageRequest.of(page, size, listOrder());
        Page<Game> games = repository.findAll(GameSpecifications.publicGames(filter, viewerId), pageable);
        Map<UUID, Seats> seats = countTakenSeats(games.getContent());
        return enrichOverview(games.map(game -> toPublicResponse(game, seats)), null);
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
    public Page<GameResponse> findOwn(
            UUID userId,
            Set<GameStatus> statuses,
            int page,
            int size
    ) {
        return findOwn(userId, statuses, page, size, GamePersonalRole.ALL, false);
    }

    /** Отбирает игры по роли до пагинации и добавляет сведения для карточек. */
    @Transactional(readOnly = true)
    public Page<GameResponse> findOwn(
            UUID userId, Set<GameStatus> statuses, int page, int size, GamePersonalRole role
    ) {
        return findOwn(userId, statuses, page, size, role, false);
    }

    /**
     * При {@code moderatorHidden=true} возвращает только мягко удалённые игры
     * их мастеру. Исходный игровой статус при модерации сохраняется, поэтому
     * этот отбор отделён от {@link GameStatus}.
     */
    @Transactional(readOnly = true)
    public Page<GameResponse> findOwn(
            UUID userId,
            Set<GameStatus> statuses,
            int page,
            int size,
            GamePersonalRole role,
            boolean moderatorHidden
    ) {
        PageRequest pageable = PageRequest.of(page, size, listOrder());
        // Без отбора отменённые не показываются: они не состоялись, и в общем
        // списке своих игр им место только по прямому запросу.
        Set<GameStatus> wanted = statuses.isEmpty()
                ? moderatorHidden
                    ? EnumSet.allOf(GameStatus.class)
                    : EnumSet.complementOf(EnumSet.of(GameStatus.CANCELLED))
                : statuses;
        Page<Game> games;
        if (moderatorHidden) {
            games = role == GamePersonalRole.ALL || role == GamePersonalRole.MASTER
                    ? repository.findAllByMasterIdAndDeletedAtIsNotNullAndStatusIn(userId, wanted, pageable)
                    : Page.empty(pageable);
        } else {
            games = role == GamePersonalRole.ALL
                    ? repository.findAllOwnOrJoinedByStatus(userId, wanted, pageable)
                    : repository.findPersonal(userId, wanted, role.name(), Instant.now(), pageable);
        }
        Map<UUID, Seats> seats = countTakenSeats(games.getContent());

        return enrichOverview(games.map(game -> game.getMasterId().equals(userId)
                ? toResponse(game, seats)
                : toPublicResponse(game, seats)), userId);
    }

    /** Загружает встречи и собственные заявки пакетно, без запроса на каждую карточку. */
    private Page<GameResponse> enrichOverview(Page<GameResponse> games, UUID userId) {
        if (games.isEmpty()) return games;
        List<UUID> gameIds = games.stream().map(GameResponse::id).toList();
        Map<UUID, NextGameSessionResponse> upcoming = new LinkedHashMap<>();
        for (GameSession session : sessionRepository.findUpcoming(gameIds, GameSessionStatus.SCHEDULED, Instant.now())) {
            upcoming.putIfAbsent(session.getGameId(), new NextGameSessionResponse(
                    session.getId(), session.getStartsAt(), session.getEstimatedDurationMinutes(),
                    session.getPriceAmount(), session.getPriceCurrency()));
        }
        Map<UUID, RegistrationStatus> registrations = userId == null ? Map.of()
                : registrationRepository.findAllByPlayerIdAndGameIdIn(userId, gameIds).stream()
                    .collect(Collectors.toMap(GameRegistration::getGameId, GameRegistration::getStatus));
        return games.map(game -> game.withOverview(upcoming.get(game.id()), registrations.get(game.id())));
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

        GameResponse response = toResponse(game, countTakenSeats(List.of(game)))
                .copyWithoutInviteCode();

        // Чат игры — разговор уже собранной группы: его видит тот, кого мастер
        // принял. Подавшему заявку он ещё не полагается: решение по нему не
        // принято, а ссылку назад не отберёшь.
        return isApprovedPlayer(gameId, requesterId) ? response : response.copyWithoutGameChat();
    }

    @Transactional
    public void delete(UUID moderatorId, UUID gameId, String reason) {
        Game game = repository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        hide(game, reason, Instant.now());
        repository.save(game);
        notifyHidden(game, moderatorId);
    }

    /**
     * Отменяет модераторское скрытие игры. Исходный жизненный цикл сохраняется:
     * открытая игра снова набирает игроков, а черновик или завершённая игра не
     * превращаются в открытое объявление.
     *
     * @param gameId Игра.
     */
    @Transactional
    public void restore(UUID gameId) {
        Game game = repository.findByIdForUpdate(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (game.getDeletedAt() == null) {
            throw new InvalidGameDetailsException("Игра не скрыта");
        }

        game.setDeletedAt(null);
        game.setDeletionReason(null);
        if (game.getStatus() == GameStatus.OPEN) {
            game.setRecruitmentClosed(false);
        }

        repository.save(game);
    }

    /**
     * Скрывает все активные игры мастера, которому принадлежит указанная игра.
     * Саму игру ищем без отбора по `deletedAt`: модератор может сначала скрыть
     * одну игру из жалобы, а затем принять решение о всех объявлениях автора.
     */
    @Transactional
    public void deleteAllByReportedGame(UUID moderatorId, UUID reportedGameId, String reason) {
        Game reportedGame = repository.findById(reportedGameId)
                .orElseThrow(() -> new GameNotFoundException(reportedGameId));
        Instant deletedAt = Instant.now();

        repository.findAllByMasterIdAndDeletedAtIsNull(reportedGame.getMasterId())
                .forEach(game -> {
                    hide(game, reason, deletedAt);
                    notifyHidden(game, moderatorId);
                });
    }

    /** Записывает единые данные мягкого удаления для одного решения модератора. */
    private static void hide(Game game, String reason, Instant deletedAt) {
        game.setDeletedAt(deletedAt);
        game.setDeletionReason(reason == null ? null : reason.trim());
    }

    /** Сообщает владельцу, какое объявление и почему скрыл модератор. */
    private void notifyHidden(Game game, UUID moderatorId) {
        notificationService.notifyUser(
                game.getMasterId(),
                moderatorId,
                NotificationType.GAME_HIDDEN_BY_MODERATOR,
                game.getId(),
                game.getTitle(),
                null,
                null,
                game.getDeletionReason());
    }

    /**
     * Порядок списков игр: сначала поднятые и свежие, затем по {@code id} —
     * второй ключ делает страницы стабильными при равных {@code listPositionAt}.
     */
    private static Sort listOrder() {
        return Sort.by(Sort.Direction.DESC, "listPositionAt")
                .and(Sort.by(Sort.Direction.DESC, "id"));
    }

    /**
     * Принят ли пользователь в игру. Аноним — нет: заявок у него не бывает.
     */
    private boolean isApprovedPlayer(UUID gameId, UUID requesterId) {
        return requesterId != null
                && registrationRepository.existsByGameIdAndPlayerIdAndStatus(
                        gameId, requesterId, RegistrationStatus.APPROVED);
    }

    private GameResponse toPublicResponse(Game game) {
        return toPublicResponse(game, countTakenSeats(List.of(game)));
    }

    /**
     * Ответ для списка: без кода приглашения и без чата игры. В карточке
     * выдачи ссылке на разговор группы делать нечего, а проверять принятых на
     * каждую строку — лишний запрос ради невидимого поля.
     */
    private GameResponse toPublicResponse(Game game, Map<UUID, Seats> seats) {
        return toResponse(game, seats).copyWithoutInviteCode().copyWithoutGameChat();
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
     * Проверки, общие для создания и редактирования: город с местом встречи
     * только у офлайна и непротиворечивые возрастные границы.
     */
    private void validateDetails(
            GameType type,
            String city,
            String venue,
            Integer minAge,
            Integer maxAge
    ) {
        if (type == GameType.ONLINE && city != null) {
            throw new InvalidGameDetailsException("Город можно указывать только для офлайн-игры");
        }
        if (city != null && city.isBlank()) {
            throw new InvalidGameDetailsException("Город не может быть пустой строкой");
        }
        if (type == GameType.ONLINE && venue != null) {
            throw new InvalidGameDetailsException(
                    "Место проведения можно указывать только для офлайн-игры");
        }
        if (venue != null && venue.isBlank()) {
            throw new InvalidGameDetailsException("Место проведения не может быть пустой строкой");
        }
        if (minAge != null && maxAge != null && minAge > maxAge) {
            throw new InvalidGameDetailsException("Минимальный возраст не может превышать максимальный");
        }
    }

    /**
     * Пускает к созданию игры только подтверждённый адрес.
     *
     * Спрашивается у auth-service тем же токеном, с которым пришёл запрос:
     * подтверждение приходит между выдачей токена и созданием игры, и по
     * старому токену мастер получил бы отказ уже после подтверждения.
     */
    private void requireVerifiedEmail(String accessToken) {
        if (!authAccountClient.isEmailVerified(accessToken)) {
            throw new UnverifiedEmailException();
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
