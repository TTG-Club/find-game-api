package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.GameResponse;
import club.ttg.findgame.game.api.GameSearchFilter;
import club.ttg.findgame.subscription.SubscriptionStatusClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;

import java.util.Set;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private final GameMapper mapper = Mappers.getMapper(GameMapper.class);

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
    void sortsSearchResultsFromNewestToOldest() {
        when(repository.findAll(any(Specification.class), any(Pageable.class))).thenReturn(Page.empty());

        service().findPublic(GameSearchFilter.empty(), 2, 15);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(15);
        assertThat(pageable.getValue().getSort().getOrderFor("createdAt").getDirection())
                .isEqualTo(Sort.Direction.DESC);
        assertThat(pageable.getValue().getSort().getOrderFor("id").getDirection())
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void nonSubscriberCannotCreateSecondUnfinishedGame() {
        UUID masterId = UUID.randomUUID();
        when(repository.existsByMasterIdAndStatusNotAndDeletedAtIsNull(masterId, GameStatus.CLOSED))
                .thenReturn(true);

        assertThatThrownBy(() -> service().create(
                masterId, "game-master", request(3, 5, GameVisibility.PUBLIC)))
                .isInstanceOf(ActiveGameLimitExceededException.class);

        verify(creationLockService).lock(masterId);
        verify(repository, never()).save(any());
    }

    @Test
    void activeSubscriberCanCreateUnlimitedGames() {
        UUID masterId = UUID.randomUUID();
        when(subscriptionStatusClient.status("subscriber")).thenReturn(Optional.of(
                new SubscriptionStatusClient.SubscriptionStatus(true, true, null, null, "BUY")));
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        service().create(masterId, "subscriber", request(3, 5, GameVisibility.PUBLIC));

        verify(creationLockService, never()).lock(any());
        verify(repository, never()).existsByMasterIdAndStatusNotAndDeletedAtIsNull(any(), any());
        verify(repository).save(any(Game.class));
    }

    @Test
    void subscriptionServiceFailureUsesFreeLimit() {
        UUID masterId = UUID.randomUUID();
        when(subscriptionStatusClient.status("game-master")).thenReturn(Optional.empty());
        when(repository.existsByMasterIdAndStatusNotAndDeletedAtIsNull(masterId, GameStatus.CLOSED))
                .thenReturn(true);

        assertThatThrownBy(() -> service().create(
                masterId, "game-master", request(3, 5, GameVisibility.PUBLIC)))
                .isInstanceOf(ActiveGameLimitExceededException.class);
    }

    @Test
    void ownerCanCloseGame() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = new Game();
        game.setMasterId(masterId);
        game.setStatus(GameStatus.OPEN);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        service().close(masterId, gameId);

        assertThat(game.getStatus()).isEqualTo(GameStatus.CLOSED);
        verify(repository).save(game);
    }

    @Test
    void anotherUserCannotCloseGame() {
        UUID gameId = UUID.randomUUID();
        Game game = new Game();
        game.setMasterId(UUID.randomUUID());
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().close(UUID.randomUUID(), gameId))
                .isInstanceOf(GameAccessDeniedException.class);

        verify(repository, never()).save(any());
    }

    private GameService service() {
        return new GameService(repository, mapper, subscriptionStatusClient, creationLockService);
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
