package club.ttg.findgame.review;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameStatus;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.registration.SessionAttendanceStatus;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.review.api.CreateGameReviewRequest;
import club.ttg.findgame.review.api.GameReviewResponse;
import club.ttg.findgame.review.api.PendingReviewResponse;
import club.ttg.findgame.review.api.ReputationResponse;
import club.ttg.findgame.review.api.ReviewTargetResponse;
import club.ttg.findgame.session.GameSessionStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Взаимные оценки за игру.
 *
 * Игрок отвечает про мастера, мастер — про каждого игрока. Оценка бинарная:
 * «сыграл бы снова» или нет — на малых числах это честнее пятизвёздочной
 * шкалы, которая быстро схлопывается в сплошные пятёрки.
 *
 * Право оценить не зависит от того, ведёт ли мастер сессии на сайте: игрок,
 * принятый неделю назад, уже успел сыграть. Иначе мастеру с плохими отзывами
 * было бы выгодно просто не закрывать встречи.
 *
 * Пока не высказались обе стороны, оценка видна только её автору: увидевший
 * первым отвечал бы тем же.
 */
@Service
public class GameReviewService {

    /**
     * Сколько игрок должен пробыть в составе, чтобы оценивать без встреч на
     * сайте. Неделя отсекает «принял друга — получил лайк» в тот же вечер.
     */
    public static final Duration MEMBERSHIP_DELAY = Duration.ofDays(7);

    /**
     * Незакрытая встреча, начавшаяся раньше этого срока, считается прошедшей:
     * мастер мог просто забыть её закрыть.
     */
    public static final Duration SESSION_GRACE = Duration.ofDays(1);

    /**
     * Сколько ждать ответа второй стороны. Потом оценка раскрывается и без
     * него, а поправить её уже нельзя.
     */
    public static final Duration REVEAL_DELAY = Duration.ofDays(14);

    /**
     * Сколько после конца игры она остаётся в списке ждущих оценки. Оценить
     * можно и позже, но напоминать о давней игре — шум.
     */
    public static final Duration PENDING_AFTER_FINISH = Duration.ofDays(30);

    private static final Set<GameSessionStatus> OPEN_SESSION_STATUSES =
            Set.of(GameSessionStatus.SCHEDULED, GameSessionStatus.IN_PROGRESS);

    private final GameReviewRepository reviewRepository;
    private final GameRepository gameRepository;
    private final SessionRegistrationRepository participationRepository;
    private final GameRegistrationRepository registrationRepository;

    public GameReviewService(
            GameReviewRepository reviewRepository,
            GameRepository gameRepository,
            SessionRegistrationRepository participationRepository,
            GameRegistrationRepository registrationRepository
    ) {
        this.reviewRepository = reviewRepository;
        this.gameRepository = gameRepository;
        this.participationRepository = participationRepository;
        this.registrationRepository = registrationRepository;
    }

    /**
     * Ставит оценку участнику игры; повторная правит поставленную, пока та не
     * раскрыта.
     *
     * @param authorId Кто оценивает.
     * @param gameId Игра.
     * @param request Кого и как оценили.
     * @return Поставленная оценка.
     */
    @Transactional
    public GameReviewResponse review(UUID authorId, UUID gameId, CreateGameReviewRequest request) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        UUID targetId = request.targetId();

        if (authorId.equals(targetId)) {
            throw new ReviewNotAllowedException("Себя не оценивают");
        }

        ReviewKind kind = resolveKind(game, authorId, targetId, Instant.now());

        GameReview review = reviewRepository
                .findByGameIdAndAuthorIdAndTargetId(gameId, authorId, targetId)
                .orElseGet(() -> GameReview.of(gameId, authorId, targetId, kind));

        if (!isEditable(review, revealEdge())) {
            throw new ReviewLockedException();
        }

        review.setRecommended(Boolean.TRUE.equals(request.recommended()));
        review.setComment(normalize(request.comment()));

        GameReview saved = reviewRepository.save(review);

        revealPair(saved);

        return toResponse(saved);
    }

    /**
     * Кого пользователь вправе оценить в игре, со своими оценками.
     *
     * Мастер видит всех сыгравших игроков, игрок — мастера. Пусто — оценивать
     * пока некого.
     *
     * @param userId Кто смотрит.
     * @param gameId Игра.
     */
    @Transactional(readOnly = true)
    public List<ReviewTargetResponse> findTargets(UUID userId, UUID gameId) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));
        Instant now = Instant.now();
        Instant edge = revealEdge();

        Map<UUID, GameReview> own = reviewRepository.findAllByGameIdAndAuthorId(gameId, userId)
                .stream()
                .collect(Collectors.toMap(GameReview::getTargetId, Function.identity()));

        List<UUID> targets;
        ReviewKind kind;

        if (game.getMasterId().equals(userId)) {
            targets = List.copyOf(playedPlayerIds(game, now));
            kind = ReviewKind.PLAYER_REVIEW;
        } else {
            targets = playedPlayerIds(game, now).contains(userId)
                    ? List.of(game.getMasterId())
                    : List.of();
            kind = ReviewKind.MASTER_REVIEW;
        }

        return targets.stream()
                .map(targetId -> {
                    GameReview review = own.get(targetId);

                    return new ReviewTargetResponse(
                            targetId,
                            kind,
                            review == null ? null : toResponse(review),
                            review == null || isEditable(review, edge));
                })
                .toList();
    }

    /**
     * Игры, где пользователь ещё не оценил тех, с кем играл: мастер — своих
     * игроков, игрок — мастеров.
     *
     * @param userId Пользователь из токена.
     */
    @Transactional(readOnly = true)
    public List<PendingReviewResponse> findPending(UUID userId) {
        Instant now = Instant.now();
        List<PendingReviewResponse> pending = new ArrayList<>();

        List<Game> hosted = gameRepository.findAllByMasterIdAndDeletedAtIsNull(userId).stream()
                .filter(game -> isPendingRelevant(game, now))
                .toList();

        for (Game game : hosted) {
            Set<UUID> reviewed = reviewedTargets(game.getId(), userId);
            List<UUID> missing = playedPlayerIds(game, now).stream()
                    .filter(playerId -> !reviewed.contains(playerId))
                    .toList();

            if (!missing.isEmpty()) {
                pending.add(new PendingReviewResponse(
                        game.getId(), game.getTitle(), ReviewKind.PLAYER_REVIEW, missing));
            }
        }

        Set<UUID> joinedGameIds = new LinkedHashSet<>(
                registrationRepository.findGameIdsApprovedBefore(userId, now.minus(MEMBERSHIP_DELAY)));
        joinedGameIds.addAll(participationRepository.findPlayedGameIds(
                userId,
                SessionAttendanceStatus.NOT_ATTENDING,
                GameSessionStatus.COMPLETED,
                OPEN_SESSION_STATUSES,
                now.minus(SESSION_GRACE)));

        if (joinedGameIds.isEmpty()) {
            return pending;
        }

        Set<UUID> reviewedGames = reviewRepository.findAllByAuthorIdAndGameIdIn(userId, joinedGameIds)
                .stream()
                .filter(review -> review.getKind() == ReviewKind.MASTER_REVIEW)
                .map(GameReview::getGameId)
                .collect(Collectors.toSet());

        gameRepository.findAllById(joinedGameIds).stream()
                .filter(game -> game.getDeletedAt() == null)
                .filter(game -> !game.getMasterId().equals(userId))
                .filter(game -> !reviewedGames.contains(game.getId()))
                .filter(game -> isPendingRelevant(game, now))
                .sorted(Comparator.comparing(Game::getTitle))
                .map(game -> new PendingReviewResponse(
                        game.getId(), game.getTitle(), ReviewKind.MASTER_REVIEW,
                        List.of(game.getMasterId())))
                .forEach(pending::add);

        return pending;
    }

    /**
     * Кто в игре кого ещё не оценил: мастер — сыгравших игроков, игроки —
     * мастера. Ключ — автор, значение — кого он не оценил.
     *
     * @param game Игра.
     * @param now Момент проверки.
     */
    @Transactional(readOnly = true)
    public Map<UUID, List<UUID>> findMissing(Game game, Instant now) {
        Set<UUID> players = playedPlayerIds(game, now);

        if (players.isEmpty()) {
            return Map.of();
        }

        Map<UUID, Set<UUID>> reviewed = reviewRepository.findAllByGameId(game.getId()).stream()
                .collect(Collectors.groupingBy(
                        GameReview::getAuthorId,
                        Collectors.mapping(GameReview::getTargetId, Collectors.toSet())));

        Map<UUID, List<UUID>> missing = new LinkedHashMap<>();
        Set<UUID> masterReviewed = reviewed.getOrDefault(game.getMasterId(), Set.of());
        List<UUID> unreviewedPlayers = players.stream()
                .filter(playerId -> !masterReviewed.contains(playerId))
                .toList();

        if (!unreviewedPlayers.isEmpty()) {
            missing.put(game.getMasterId(), unreviewedPlayers);
        }

        players.stream()
                .filter(playerId -> !reviewed.getOrDefault(playerId, Set.of())
                        .contains(game.getMasterId()))
                .forEach(playerId -> missing.put(playerId, List.of(game.getMasterId())));

        return missing;
    }

    /**
     * Репутация мастера: её видно всем, кто смотрит объявление.
     * @param masterId Мастер.
     */
    @Transactional(readOnly = true)
    public ReputationResponse getMasterReputation(UUID masterId) {
        return reputation(masterId, ReviewKind.MASTER_REVIEW);
    }

    /**
     * Отзывы о мастере: их читают в его профиле.
     * @param masterId Мастер.
     */
    @Transactional(readOnly = true)
    public List<GameReviewResponse> findMasterReviews(UUID masterId) {
        return reviewRepository.findVisible(masterId, ReviewKind.MASTER_REVIEW, revealEdge())
                .stream()
                .map(GameReviewService::toResponse)
                .toList();
    }

    /**
     * Репутация игрока для мастера, разбирающего его заявку.
     *
     * Читает её только мастер игры, и только пока игрок в неё просится: отзывы
     * об игроках — разговор мастеров между собой, а не публичная страница.
     *
     * @param masterId Мастер из токена.
     * @param gameId Игра, куда подана заявка.
     * @param playerId Игрок.
     */
    @Transactional(readOnly = true)
    public ReputationResponse getPlayerReputation(UUID masterId, UUID gameId, UUID playerId) {
        requireApplicantOfOwnGame(masterId, gameId, playerId);

        return reputation(playerId, ReviewKind.PLAYER_REVIEW);
    }

    /**
     * Отзывы об игроке для мастера, разбирающего его заявку.
     *
     * @param masterId Мастер из токена.
     * @param gameId Игра, куда подана заявка.
     * @param playerId Игрок.
     */
    @Transactional(readOnly = true)
    public List<GameReviewResponse> findPlayerReviews(
            UUID masterId,
            UUID gameId,
            UUID playerId
    ) {
        requireApplicantOfOwnGame(masterId, gameId, playerId);

        return reviewRepository.findVisible(playerId, ReviewKind.PLAYER_REVIEW, revealEdge())
                .stream()
                .map(GameReviewService::toResponse)
                .toList();
    }

    /**
     * Своя репутация игрока: доля и число оценок без текстов и авторов.
     *
     * Игрок знает, где стоит, но не идёт выяснять отношения с конкретным
     * мастером — иначе отзывы стали бы осторожными и бесполезными.
     *
     * @param userId Пользователь из токена.
     */
    @Transactional(readOnly = true)
    public ReputationResponse getOwnPlayerReputation(UUID userId) {
        return reputation(userId, ReviewKind.PLAYER_REVIEW);
    }

    /**
     * Кто из игроков в игре успел сыграть: принят больше недели назад либо
     * побывал на прошедшей встрече.
     */
    private Set<UUID> playedPlayerIds(Game game, Instant now) {
        Set<UUID> players = new LinkedHashSet<>(registrationRepository.findPlayerIdsApprovedBefore(
                game.getId(), now.minus(MEMBERSHIP_DELAY)));

        players.addAll(participationRepository.findPlayedPlayerIds(
                game.getId(),
                SessionAttendanceStatus.NOT_ATTENDING,
                GameSessionStatus.COMPLETED,
                OPEN_SESSION_STATUSES,
                now.minus(SESSION_GRACE)));

        players.remove(game.getMasterId());

        return players;
    }

    /** Кто кого оценивает: мастер — игрока, игрок — мастера. */
    private ReviewKind resolveKind(Game game, UUID authorId, UUID targetId, Instant now) {
        if (game.getMasterId().equals(authorId)) {
            if (!playedPlayerIds(game, now).contains(targetId)) {
                throw new ReviewNotAllowedException("Этот игрок в игре ещё не играл");
            }

            return ReviewKind.PLAYER_REVIEW;
        }

        if (!game.getMasterId().equals(targetId)) {
            throw new ReviewNotAllowedException("Игроки оценивают мастера игры");
        }

        if (!playedPlayerIds(game, now).contains(authorId)) {
            throw new ReviewNotAllowedException("Оценку ставит тот, кто уже играл в этой игре");
        }

        return ReviewKind.MASTER_REVIEW;
    }

    /**
     * Свою оценку правят, пока она скрыта. Раскрытая уже прочитана второй
     * стороной: поменять её — значит ответить на чужой вердикт.
     */
    private static boolean isEditable(GameReview review, Instant revealEdge) {
        if (review.getCreatedAt() == null) {
            return true;
        }

        return review.getVisibleAt() == null && !review.getCreatedAt().isBefore(revealEdge);
    }

    /**
     * Раскрывает пару, когда высказалась вторая сторона.
     *
     * Раскрываются обе оценки разом: одна открытая при второй скрытой — то же
     * самое неравенство, ради которого их и прятали.
     */
    private void revealPair(GameReview review) {
        Optional<GameReview> counterpart = reviewRepository
                .findByGameIdAndAuthorIdAndTargetId(
                        review.getGameId(), review.getTargetId(), review.getAuthorId());

        if (counterpart.isEmpty()) {
            return;
        }

        Instant now = Instant.now();
        GameReview other = counterpart.get();

        if (review.getVisibleAt() == null) {
            review.setVisibleAt(now);
            reviewRepository.save(review);
        }

        if (other.getVisibleAt() == null) {
            other.setVisibleAt(now);
            reviewRepository.save(other);
        }
    }

    private Set<UUID> reviewedTargets(UUID gameId, UUID authorId) {
        return reviewRepository.findAllByGameIdAndAuthorId(gameId, authorId).stream()
                .map(GameReview::getTargetId)
                .collect(Collectors.toSet());
    }

    /** Мастер разбирает заявку этого игрока в свою игру. */
    private void requireApplicantOfOwnGame(UUID masterId, UUID gameId, UUID playerId) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (!game.getMasterId().equals(masterId)) {
            throw new ReviewNotAllowedException("Отзывы об игроках читает мастер игры");
        }

        boolean applied = registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                gameId, playerId, RegistrationStatus.REJECTED);

        if (!applied) {
            throw new ReviewNotAllowedException("Этот игрок в игру не просится");
        }
    }

    /**
     * Доля «сыграл бы снова» среди разных людей.
     *
     * От автора считается одна, последняя оценка: друг, сыгравший у мастера
     * пять игр, — всё ещё один человек, а не пять голосов.
     */
    private ReputationResponse reputation(UUID userId, ReviewKind kind) {
        Map<UUID, GameReview> latestByAuthor = new LinkedHashMap<>();

        reviewRepository.findVisible(userId, kind, revealEdge())
                .forEach(review -> latestByAuthor.putIfAbsent(review.getAuthorId(), review));

        long recommended = latestByAuthor.values().stream()
                .filter(GameReview::isRecommended)
                .count();

        return new ReputationResponse(userId, recommended, latestByAuthor.size());
    }

    /** Идущая игра или завершённая недавно: о ней ещё уместно напоминать. */
    private static boolean isPendingRelevant(Game game, Instant now) {
        if (game.getStatus() == GameStatus.OPEN) {
            return true;
        }

        if (game.getStatus() == GameStatus.DRAFT) {
            return false;
        }

        return game.getUpdatedAt() != null
                && game.getUpdatedAt().isAfter(now.minus(PENDING_AFTER_FINISH));
    }

    /** Оценки старше этой отметки раскрыты в любом случае: ответа не дождались. */
    private static Instant revealEdge() {
        return Instant.now().minus(REVEAL_DELAY);
    }

    private static String normalize(String value) {
        if (value == null) {
            return null;
        }

        String normalized = value.strip();

        return normalized.isEmpty() ? null : normalized;
    }

    private static GameReviewResponse toResponse(GameReview review) {
        return new GameReviewResponse(
                review.getId(),
                review.getGameId(),
                review.getAuthorId(),
                review.getTargetId(),
                review.getKind(),
                review.isRecommended(),
                review.getComment(),
                review.getCreatedAt());
    }
}
