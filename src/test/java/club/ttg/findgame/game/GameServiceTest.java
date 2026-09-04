package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.GameResponse;
import club.ttg.findgame.game.api.GameSearchFilter;
import club.ttg.findgame.game.api.UpdateGameRequest;
import club.ttg.findgame.registration.GameSeatCount;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.session.GameSession;
import club.ttg.findgame.session.GameSessionStatus;
import club.ttg.findgame.session.GameSessionRepository;
import club.ttg.findgame.subscription.SubscriptionStatusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameServiceTest {

    @Mock
    private GameRepository repository;

    @Mock
    private GameRaiseRepository raiseRepository;

    @Mock
    private SubscriptionStatusClient subscriptionStatusClient;

    @Mock
    private GameCreationLockService creationLockService;

    @Mock
    private GameSessionRepository sessionRepository;

    @Mock
    private GameRegistrationRepository registrationRepository;

    private final GameMapper mapper = Mappers.getMapper(GameMapper.class);

    @Test
    void newGameStartsAtItsCreationPosition() {
        Game game = new Game();

        game.prePersist();

        assertThat(game.getListPositionAt()).isEqualTo(game.getCreatedAt());
    }

    @Test
    void createsPrivateGameAndReturnsInviteCode() {
        GameService service = service();
        UUID masterId = UUID.randomUUID();
        CreateGameRequest request = request(3, 5, GameVisibility.PRIVATE);
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GameResponse response = service.create(masterId, "game-master", request);

        ArgumentCaptor<Game> captor = ArgumentCaptor.forClass(Game.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getMasterId()).isEqualTo(masterId);
        assertThat(response.virtualTableUrl()).isEqualTo("https://vtt.example.org/games/curse-of-strahd");
        assertThat(response.genre()).isEqualTo("Готическое фэнтези");
        assertThat(response.durationType()).isEqualTo(GameDurationType.CAMPAIGN);
        assertThat(response.costType()).isEqualTo(GameCostType.PAID);
        assertThat(response.minAge()).isEqualTo(18);
        assertThat(response.maxAge()).isEqualTo(99);
        assertThat(response.startingLevel()).isEqualTo(1);
        assertThat(response.crossplayAllowed()).isTrue();
        assertThat(response.status()).isEqualTo(GameStatus.OPEN);
        assertThat(response.allowedSources()).containsExactlyInAnyOrder(
                "Player's Handbook 2024", "Tasha's Cauldron of Everything");
        assertThat(response.inviteCode()).isNotNull();
    }

    @Test
    void freeMasterDoesNotSeatMoreThanFivePlayers() {
        GameService service = service();

        assertThatThrownBy(() -> service.create(
                UUID.randomUUID(), "game-master", request(3, 6, GameVisibility.PUBLIC)))
                .isInstanceOf(InvalidPlayerCountException.class);

        verify(repository, never()).save(any(Game.class));
    }

    @Test
    void subscriberSeatsUpToFifteenPlayers() {
        GameService service = service();
        when(subscriptionStatusClient.status("game-master")).thenReturn(
                Optional.of(new SubscriptionStatusClient.SubscriptionStatus(true, true, null, null, "PREMIUM")));
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GameResponse response = service.create(
                UUID.randomUUID(), "game-master", request(3, 15, GameVisibility.PUBLIC));

        assertThat(response.maxPlayers()).isEqualTo(15);
    }

    @Test
    void subscriberStillDoesNotSeatMoreThanFifteenPlayers() {
        GameService service = service();
        when(subscriptionStatusClient.status("game-master")).thenReturn(
                Optional.of(new SubscriptionStatusClient.SubscriptionStatus(true, true, null, null, "PREMIUM")));

        assertThatThrownBy(() -> service.create(
                UUID.randomUUID(), "game-master", request(3, 16, GameVisibility.PUBLIC)))
                .isInstanceOf(InvalidPlayerCountException.class);
    }

    @Test
    void rejectsPlayersToStartGreaterThanMaximum() {
        GameService service = service();

        assertThatThrownBy(() -> service.create(
                UUID.randomUUID(), "game-master", request(6, 5, GameVisibility.PUBLIC)))
                .isInstanceOf(InvalidPlayerCountException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsCityForOnlineGame() {
        GameService service = service();
        CreateGameRequest source = request(3, 5, GameVisibility.PUBLIC);
        CreateGameRequest request = new CreateGameRequest(
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(),
                source.masterChatUrl(), source.gameChatUrl(), source.genre(),
                source.description(), source.requirements(), source.allowedSources(), source.type(),
                "Кишинёв", source.venue(),
                source.playersToStart(), source.maxPlayers(), source.minAge(), source.maxAge(),
                source.startingLevel(), source.crossplayAllowed(), source.durationType(), source.costType(),
                source.visibility());

        assertThatThrownBy(() -> service.create(UUID.randomUUID(), "game-master", request))
                .isInstanceOf(InvalidGameDetailsException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void rejectsVenueForOnlineGame() {
        GameService service = service();
        CreateGameRequest source = request(3, 5, GameVisibility.PUBLIC);
        CreateGameRequest request = new CreateGameRequest(
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(),
                source.masterChatUrl(), source.gameChatUrl(), source.genre(),
                source.description(), source.requirements(), source.allowedSources(), source.type(),
                source.city(), "Клуб «Кубик», Пятницкая 12",
                source.playersToStart(), source.maxPlayers(), source.minAge(), source.maxAge(),
                source.startingLevel(), source.crossplayAllowed(), source.durationType(), source.costType(),
                source.visibility());

        // Онлайн собирается по ссылке: адрес стола ему не нужен.
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), "game-master", request))
                .isInstanceOf(InvalidGameDetailsException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void softDeletesExistingGameWithoutPhysicalRemoval() {
        UUID gameId = UUID.randomUUID();
        Game game = new Game();
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        service().delete(gameId, "  Нарушение правил  ");

        verify(repository).save(game);
        verify(repository, never()).delete(any(Game.class));
        assertThat(game.getDeletedAt()).isNotNull();
        assertThat(game.getDeletionReason()).isEqualTo("Нарушение правил");
    }

    @Test
    void deleteReturnsNotFoundForMissingGame() {
        UUID gameId = UUID.randomUUID();
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().delete(gameId, null))
                .isInstanceOf(GameNotFoundException.class);
        verify(repository, never()).save(any(Game.class));
    }

    @Test
    void acceptsOnlyMinimumAge() {
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreateGameRequest request = withAges(request(3, 5, GameVisibility.PUBLIC), 18, null);

        GameResponse response = service().create(UUID.randomUUID(), "game-master", request);

        assertThat(response.minAge()).isEqualTo(18);
        assertThat(response.maxAge()).isNull();
    }

    @Test
    void acceptsOnlyMaximumAge() {
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));
        CreateGameRequest request = withAges(request(3, 5, GameVisibility.PUBLIC), null, 30);

        GameResponse response = service().create(UUID.randomUUID(), "game-master", request);

        assertThat(response.minAge()).isNull();
        assertThat(response.maxAge()).isEqualTo(30);
    }

    @Test
    void rejectsInvertedAgeRange() {
        CreateGameRequest request = withAges(request(3, 5, GameVisibility.PUBLIC), 30, 18);

        assertThatThrownBy(() -> service().create(UUID.randomUUID(), "game-master", request))
                .isInstanceOf(InvalidGameDetailsException.class);
        verify(repository, never()).save(any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void sortsSearchResultsByLatestListPosition() {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service().findPublic(GameSearchFilter.empty(), 2, 15);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(15);
        assertThat(pageable.getValue().getSort().getOrderFor("listPositionAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @SuppressWarnings("unchecked")
    @Test
    void searchResultsCarrySeatsTakenInGame() {
        Game game = raisableGame(UUID.randomUUID(), Instant.now());
        game.setId(UUID.randomUUID());
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(game)));
        when(registrationRepository.countTakenSeatsByGame(
                List.of(game.getId()),
                RegistrationStatus.REJECTED,
                RegistrationStatus.APPROVED))
                .thenReturn(List.of(seatCount(game.getId(), 2, 2)));

        Page<GameResponse> found = service().findPublic(GameSearchFilter.empty(), 0, 20);

        // Игрок записывается в игру целиком, поэтому занятость считается по её
        // заявкам, а не по отдельной встрече.
        assertThat(found.getContent()).singleElement()
                .satisfies(response -> assertThat(response.takenSeats()).isEqualTo(2));
    }

    @SuppressWarnings("unchecked")
    @Test
    void gameWithoutRegistrationsReportsZeroSeatsTaken() {
        Game game = raisableGame(UUID.randomUUID(), Instant.now());
        game.setId(UUID.randomUUID());
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(game)));
        when(registrationRepository.countTakenSeatsByGame(
                List.of(game.getId()),
                RegistrationStatus.REJECTED,
                RegistrationStatus.APPROVED))
                .thenReturn(List.of());

        Page<GameResponse> found = service().findPublic(GameSearchFilter.empty(), 0, 20);

        // Игры без заявок в групповой выдаче нет вовсе — это ноль, а не пропуск.
        assertThat(found.getContent()).singleElement()
                .satisfies(response -> assertThat(response.takenSeats()).isZero());
    }

    @SuppressWarnings("unchecked")
    @Test
    void confirmedSeatsAreCountedApartFromPendingOnes() {
        Game game = raisableGame(UUID.randomUUID(), Instant.now());
        game.setId(UUID.randomUUID());
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(game)));
        when(registrationRepository.countTakenSeatsByGame(
                List.of(game.getId()),
                RegistrationStatus.REJECTED,
                RegistrationStatus.APPROVED))
                .thenReturn(List.of(seatCount(game.getId(), 3, 1)));

        Page<GameResponse> found = service().findPublic(GameSearchFilter.empty(), 0, 20);

        // Пока мастер разбирает заявку, игрок на место уже претендует, но
        // подтверждённым оно ещё не считается — поэтому чисел два.
        assertThat(found.getContent()).singleElement()
                .satisfies(response -> {
                    assertThat(response.takenSeats()).isEqualTo(3);
                    assertThat(response.approvedSeats()).isEqualTo(1);
                });
    }

    /** Строка группового подсчёта занятых и подтверждённых мест игры. */
    private static GameSeatCount seatCount(UUID gameId, long players, long approved) {
        return new GameSeatCount() {

            @Override
            public UUID getGameId() {
                return gameId;
            }

            @Override
            public long getPlayerCount() {
                return players;
            }

            @Override
            public long getApprovedCount() {
                return approved;
            }
        };
    }

    private GameService service() {
        return new GameService(
                repository,
                raiseRepository,
                mapper,
                subscriptionStatusClient,
                creationLockService,
                sessionRepository,
                registrationRepository);
    }

    @Test
    void freeMasterRaisesGameOncePerDay() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = raisableGame(masterId, Instant.now().minus(2, ChronoUnit.HOURS));

        game.setId(gameId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(subscriptionStatusClient.status("game-master")).thenReturn(Optional.empty());
        when(raiseRepository.countByGameIdAndRaisedAtAfter(eq(gameId), any(Instant.class)))
                .thenReturn(1L);
        when(raiseRepository.findWindow(eq(gameId), any(Instant.class), any()))
                .thenReturn(List.of());

        // Норма на сутки исчерпана: без подписки поднятие одно.
        assertThatThrownBy(() -> service().raise(masterId, "game-master", gameId))
                .isInstanceOf(GameRaiseCooldownException.class);

        verify(raiseRepository, never()).save(any());
    }

    @Test
    void subscriberRaisesGameThreeTimesPerDay() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = raisableGame(masterId, Instant.now().minus(2, ChronoUnit.HOURS));

        game.setId(gameId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(subscriptionStatusClient.status("game-master")).thenReturn(
                Optional.of(new SubscriptionStatusClient.SubscriptionStatus(true, true, null, null, "PREMIUM")));
        when(raiseRepository.countByGameIdAndRaisedAtAfter(eq(gameId), any(Instant.class)))
                .thenReturn(2L);
        when(repository.save(game)).thenReturn(game);

        service().raise(masterId, "game-master", gameId);

        // Третье поднятие за сутки подписчику ещё положено.
        verify(raiseRepository).save(any(GameRaise.class));
    }

    @Test
    void spentRaiseQuotaFreesWhenOldestLeavesWindow() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = raisableGame(masterId, Instant.now().minus(2, ChronoUnit.HOURS));
        Instant oldest = Instant.now().minus(20, ChronoUnit.HOURS);
        GameRaise raise = new GameRaise();

        raise.setGameId(gameId);
        raise.setRaisedAt(oldest);
        raise.prePersist();

        game.setId(gameId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(subscriptionStatusClient.status("game-master")).thenReturn(Optional.empty());
        when(raiseRepository.countByGameIdAndRaisedAtAfter(eq(gameId), any(Instant.class)))
                .thenReturn(1L);
        when(raiseRepository.findWindow(eq(gameId), any(Instant.class), any()))
                .thenReturn(List.of(raise));

        assertThatThrownBy(() -> service().raise(masterId, "game-master", gameId))
                .isInstanceOf(GameRaiseCooldownException.class)
                .extracting(error -> ((GameRaiseCooldownException) error).getAvailableAt())
                .isEqualTo(oldest.plus(1, ChronoUnit.DAYS));
    }

    @Test
    void playerCountDoesNotDropBelowApproved() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);

        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(subscriptionStatusClient.status("game-master")).thenReturn(Optional.empty());
        when(registrationRepository.countByGameIdAndStatus(gameId, RegistrationStatus.APPROVED))
                .thenReturn(4L);

        // Четверо уже приняты: стол на троих их не вместит.
        assertThatThrownBy(() -> service().update(
                masterId, "game-master", gameId, playerCounts(3, 3)))
                .isInstanceOf(InvalidPlayerCountException.class);

        // И порог старта ниже принятых тоже бессмыслен: он давно пройден.
        assertThatThrownBy(() -> service().update(
                masterId, "game-master", gameId, playerCounts(2, 5)))
                .isInstanceOf(InvalidPlayerCountException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void maximumStaysAtLeastAsBigAsMinimum() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);

        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().update(
                masterId, "game-master", gameId, playerCounts(5, 3)))
                .isInstanceOf(InvalidPlayerCountException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void masterClosesRecruitmentWithFirstApprovedPlayer() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.countByGameIdAndStatus(gameId, RegistrationStatus.APPROVED))
                .thenReturn(1L);
        when(repository.save(game)).thenReturn(game);

        GameResponse response = service().closeRecruitment(masterId, gameId);

        assertThat(response.recruitmentClosed()).isTrue();
    }

    @Test
    void recruitmentDoesNotCloseWithoutApprovedPlayers() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.countByGameIdAndStatus(gameId, RegistrationStatus.APPROVED))
                .thenReturn(0L);

        // Объявление без единого игрока исчезло бы из поиска, ничего не собрав.
        assertThatThrownBy(() -> service().closeRecruitment(masterId, gameId))
                .isInstanceOf(InvalidGameDetailsException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void strangerDoesNotCloseRecruitment() {
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, UUID.randomUUID());
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().closeRecruitment(UUID.randomUUID(), gameId))
                .isInstanceOf(GameAccessDeniedException.class);
    }

    @Test
    void masterOpensRecruitmentWhileSeatIsFree() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        game.setRecruitmentClosed(true);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.countByGameIdAndStatusNot(gameId, RegistrationStatus.REJECTED))
                .thenReturn(4L);
        when(repository.save(game)).thenReturn(game);

        assertThat(service().openRecruitment(masterId, gameId).recruitmentClosed()).isFalse();
    }

    @Test
    void recruitmentDoesNotOpenWithoutFreeSeat() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        game.setRecruitmentClosed(true);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.countByGameIdAndStatusNot(gameId, RegistrationStatus.REJECTED))
                .thenReturn(5L);

        // Полный стол закрыт и без отметки: звать в него некуда.
        assertThatThrownBy(() -> service().openRecruitment(masterId, gameId))
                .isInstanceOf(InvalidGameDetailsException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void masterSeesBothChatLinks() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = chattyGame(gameId, masterId);
        when(repository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));

        GameResponse response = service().get(masterId, gameId, null);

        assertThat(response.masterChatUrl()).isEqualTo("https://t.me/master");
        assertThat(response.gameChatUrl()).isEqualTo("https://t.me/+strahd-party");
    }

    @Test
    void approvedPlayerSeesGameChatLink() {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = chattyGame(gameId, UUID.randomUUID());
        when(repository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.existsByGameIdAndPlayerIdAndStatus(
                gameId, playerId, RegistrationStatus.APPROVED)).thenReturn(true);

        assertThat(service().get(playerId, gameId, null).gameChatUrl())
                .isEqualTo("https://t.me/+strahd-party");
    }

    @Test
    void pendingPlayerDoesNotSeeGameChatLink() {
        UUID playerId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = chattyGame(gameId, UUID.randomUUID());
        when(repository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));
        when(registrationRepository.existsByGameIdAndPlayerIdAndStatus(
                gameId, playerId, RegistrationStatus.APPROVED)).thenReturn(false);

        GameResponse response = service().get(playerId, gameId, null);

        // Заявку ещё не разобрали: разговор группы его пока не касается, а
        // ссылку назад не отберёшь.
        assertThat(response.gameChatUrl()).isNull();
        // Договариваться о заявке нужно всем, поэтому мастер остаётся на связи.
        assertThat(response.masterChatUrl()).isEqualTo("https://t.me/master");
    }

    @Test
    void strangerSeesOnlyMasterChatLink() {
        UUID gameId = UUID.randomUUID();
        Game game = chattyGame(gameId, UUID.randomUUID());
        when(repository.findByIdAndDeletedAtIsNull(gameId)).thenReturn(Optional.of(game));

        GameResponse response = service().get(null, gameId, null);

        assertThat(response.gameChatUrl()).isNull();
        assertThat(response.masterChatUrl()).isEqualTo("https://t.me/master");
    }

    /** Игра со ссылками на разговоры. */
    private Game chattyGame(UUID gameId, UUID masterId) {
        Game game = editableGame(gameId, masterId);

        game.setMasterChatUrl("https://t.me/master");
        game.setGameChatUrl("https://t.me/+strahd-party");

        return game;
    }

    /** Игра мастера в исходном состоянии — то, что правит редактирование. */
    private Game editableGame(UUID gameId, UUID masterId) {
        Game game = new Game();

        game.setId(gameId);
        game.setMasterId(masterId);
        game.setTitle("Проклятие Страда");
        game.setSystem(GameSystem.DND_2024);
        game.setDescription("Кампания");
        game.setRequirements("Требования");
        game.setType(GameType.ONLINE);
        game.setPlayersToStart(3);
        game.setMaxPlayers(5);
        game.setStartingLevel(1);
        game.setStatus(GameStatus.OPEN);
        game.setDurationType(GameDurationType.CAMPAIGN);
        game.setCostType(GameCostType.FREE);
        game.setVisibility(GameVisibility.PUBLIC);

        return game;
    }

    /** Тело правки с заданным составом: остальное совпадает с игрой. */
    private UpdateGameRequest playerCounts(int playersToStart, int maxPlayers) {
        UpdateGameRequest source = updateRequest();

        return new UpdateGameRequest(
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(),
                source.masterChatUrl(), source.gameChatUrl(), source.genre(),
                source.description(), source.requirements(), source.allowedSources(), source.type(),
                source.city(), source.venue(),
                playersToStart, maxPlayers, source.minAge(), source.maxAge(),
                source.startingLevel(), source.crossplayAllowed(), source.durationType(),
                source.costType(), source.visibility());
    }

    /** Тело правки: по умолчанию совпадает с {@link #editableGame}. */
    private UpdateGameRequest updateRequest() {
        return new UpdateGameRequest(
                "Проклятие Страда",
                GameSystem.DND_2024,
                null,
                null,
                null,
                null,
                null,
                "Кампания",
                "Требования",
                null,
                GameType.ONLINE,
                null,
                null,
                3,
                5,
                null,
                null,
                1,
                false,
                GameDurationType.CAMPAIGN,
                GameCostType.FREE,
                GameVisibility.PUBLIC);
    }

    private Game raisableGame(UUID masterId, Instant listPositionAt) {
        Game game = new Game();
        game.setMasterId(masterId);
        game.setVisibility(GameVisibility.PUBLIC);
        game.setStatus(GameStatus.OPEN);
        game.setListPositionAt(listPositionAt);
        return game;
    }

    private CreateGameRequest withAges(CreateGameRequest source, Integer minAge, Integer maxAge) {
        return new CreateGameRequest(
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(),
                source.masterChatUrl(), source.gameChatUrl(), source.genre(),
                source.description(), source.requirements(), source.allowedSources(), source.type(),
                source.city(), source.venue(),
                source.playersToStart(), source.maxPlayers(), minAge, maxAge, source.startingLevel(),
                source.crossplayAllowed(), source.durationType(), source.costType(), source.visibility());
    }

    private CreateGameRequest request(int playersToStart, int maxPlayers, GameVisibility visibility) {
        return new CreateGameRequest(
                "Проклятие Страда",
                GameSystem.DND_2024,
                "https://example.org/strahd.jpg",
                "https://vtt.example.org/games/curse-of-strahd",
                "https://t.me/master",
                "https://t.me/+strahd-party",
                "Готическое фэнтези",
                "Готическая кампания",
                "Совершеннолетние игроки",
                Set.of("Player's Handbook 2024", "Tasha's Cauldron of Everything"),
                GameType.ONLINE,
                null,
                null,
                playersToStart,
                maxPlayers,
                18,
                99,
                1,
                true,
                GameDurationType.CAMPAIGN,
                GameCostType.PAID,
                visibility
        );
    }
}
