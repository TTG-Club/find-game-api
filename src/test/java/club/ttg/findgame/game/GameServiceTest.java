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
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(), source.genre(),
                source.description(), source.requirements(), source.allowedSources(), source.type(), "Кишинёв",
                source.playersToStart(), source.maxPlayers(), source.minAge(), source.maxAge(),
                source.startingLevel(), source.crossplayAllowed(), source.durationType(), source.costType(),
                source.visibility());

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
                mapper,
                subscriptionStatusClient,
                creationLockService,
                sessionRepository,
                registrationRepository);
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

    /** Тело правки: по умолчанию совпадает с {@link #editableGame}. */
    private UpdateGameRequest updateRequest() {
        return new UpdateGameRequest(
                "Проклятие Страда",
                GameSystem.DND_2024,
                null,
                null,
                null,
                "Кампания",
                "Требования",
                null,
                GameType.ONLINE,
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
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(), source.genre(),
                source.description(), source.requirements(), source.allowedSources(), source.type(), source.city(),
                source.playersToStart(), source.maxPlayers(), minAge, maxAge, source.startingLevel(),
                source.crossplayAllowed(), source.durationType(), source.costType(), source.visibility());
    }

    private CreateGameRequest request(int playersToStart, int maxPlayers, GameVisibility visibility) {
        return new CreateGameRequest(
                "Проклятие Страда",
                GameSystem.DND_2024,
                "https://example.org/strahd.jpg",
                "https://vtt.example.org/games/curse-of-strahd",
                "Готическое фэнтези",
                "Готическая кампания",
                "Совершеннолетние игроки",
                Set.of("Player's Handbook 2024", "Tasha's Cauldron of Everything"),
                GameType.ONLINE,
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
