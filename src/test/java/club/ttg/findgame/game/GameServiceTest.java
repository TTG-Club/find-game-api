package club.ttg.findgame.game;

import club.ttg.findgame.game.api.CreateGameRequest;
import club.ttg.findgame.game.api.GameResponse;
import club.ttg.findgame.game.api.GameSearchFilter;
import club.ttg.findgame.game.api.UpdateGameRequest;
import club.ttg.findgame.registration.GamePlayerCount;
import club.ttg.findgame.registration.SessionRegistrationRepository;
import club.ttg.findgame.registration.SessionRegistrationStatus;
import club.ttg.findgame.session.GameSession;
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
    private SessionRegistrationRepository registrationRepository;

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
    void searchResultsCarryApprovedPlayerCount() {
        Game game = raisableGame(UUID.randomUUID(), Instant.now());
        game.setId(UUID.randomUUID());
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(game)));
        when(registrationRepository.countApprovedPlayersByGame(
                List.of(game.getId()), SessionRegistrationStatus.APPROVED))
                .thenReturn(List.of(playerCount(game.getId(), 2)));

        Page<GameResponse> found = service().findPublic(GameSearchFilter.empty(), 0, 20);

        // Число занятых мест в игре не хранится: карточка каталога получает
        // его посчитанным по принятым заявкам.
        assertThat(found.getContent()).singleElement()
                .satisfies(response -> assertThat(response.approvedPlayers()).isEqualTo(2));
    }

    @SuppressWarnings("unchecked")
    @Test
    void gameWithoutApprovedPlayersReportsZeroSeatsTaken() {
        Game game = raisableGame(UUID.randomUUID(), Instant.now());
        game.setId(UUID.randomUUID());
        when(repository.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(game)));
        when(registrationRepository.countApprovedPlayersByGame(
                List.of(game.getId()), SessionRegistrationStatus.APPROVED))
                .thenReturn(List.of());

        Page<GameResponse> found = service().findPublic(GameSearchFilter.empty(), 0, 20);

        // Игры без заявок в групповой выдаче нет вовсе — это ноль, а не пропуск.
        assertThat(found.getContent()).singleElement()
                .satisfies(response -> assertThat(response.approvedPlayers()).isZero());
    }

    /** Строка группового подсчёта принятых игроков. */
    private static GamePlayerCount playerCount(UUID gameId, long players) {
        return new GamePlayerCount() {

            @Override
            public UUID getGameId() {
                return gameId;
            }

            @Override
            public long getPlayerCount() {
                return players;
            }
        };
    }

    @Test
    void ownGamesKeepInviteCodeAndIncludePrivateAndClosed() {
        UUID masterId = UUID.randomUUID();
        UUID inviteCode = UUID.randomUUID();
        Game privateClosed = raisableGame(masterId, Instant.now());
        privateClosed.setVisibility(GameVisibility.PRIVATE);
        privateClosed.setStatus(GameStatus.CLOSED);
        privateClosed.setInviteCode(inviteCode);
        when(repository.findAllByMasterIdAndDeletedAtIsNull(eq(masterId), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(privateClosed)));

        Page<GameResponse> own = service().findOwn(masterId, 0, 20);

        // Владельцу код нужен, чтобы собрать ссылку-приглашение: в публичных
        // ответах он вырезается, здесь — обязан остаться.
        assertThat(own.getContent()).singleElement()
                .satisfies(game -> {
                    assertThat(game.inviteCode()).isEqualTo(inviteCode);
                    assertThat(game.visibility()).isEqualTo(GameVisibility.PRIVATE);
                    assertThat(game.status()).isEqualTo(GameStatus.CLOSED);
                });
    }

    @Test
    void ownGamesUseTheSameStableOrderAsPublicSearch() {
        UUID masterId = UUID.randomUUID();
        when(repository.findAllByMasterIdAndDeletedAtIsNull(eq(masterId), any(Pageable.class)))
                .thenReturn(Page.empty());

        service().findOwn(masterId, 3, 10);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findAllByMasterIdAndDeletedAtIsNull(eq(masterId), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(3);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
        assertThat(pageable.getValue().getSort().getOrderFor("listPositionAt").getDirection())
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

    @Test
    void freeMasterCanRaisePublicOpenGameAfterOneDay() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = raisableGame(masterId, Instant.now().minus(25, ChronoUnit.HOURS));
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(repository.save(game)).thenReturn(game);

        GameResponse response = service().raise(masterId, "game-master", gameId);

        assertThat(response.listPositionAt()).isAfter(Instant.now().minus(1, ChronoUnit.MINUTES));
        verify(repository).save(game);
    }

    @Test
    void freeMasterCannotRaiseGameTwiceWithinOneDay() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = raisableGame(masterId, Instant.now().minus(23, ChronoUnit.HOURS));
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().raise(masterId, "game-master", gameId))
                .isInstanceOf(GameRaiseCooldownException.class)
                .satisfies(exception -> assertThat(((GameRaiseCooldownException) exception).getAvailableAt())
                        .isEqualTo(game.getListPositionAt().plus(1, ChronoUnit.DAYS)));

        verify(repository, never()).save(any());
    }

    @Test
    void activeSubscriberCanRaiseGameAfterOneHour() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = raisableGame(masterId, Instant.now().minus(61, ChronoUnit.MINUTES));
        when(subscriptionStatusClient.status("subscriber")).thenReturn(Optional.of(
                new SubscriptionStatusClient.SubscriptionStatus(true, true, null, null, "BUY")));
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(repository.save(game)).thenReturn(game);

        service().raise(masterId, "subscriber", gameId);

        verify(repository).save(game);
    }

    @Test
    void subscriptionServiceFailureUsesDailyRaiseInterval() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = raisableGame(masterId, Instant.now().minus(2, ChronoUnit.HOURS));
        when(subscriptionStatusClient.status("game-master")).thenReturn(Optional.empty());
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().raise(masterId, "game-master", gameId))
                .isInstanceOf(GameRaiseCooldownException.class);
    }

    @Test
    void cannotRaiseAnotherMastersGame() {
        UUID gameId = UUID.randomUUID();
        Game game = raisableGame(UUID.randomUUID(), Instant.now().minus(2, ChronoUnit.DAYS));
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().raise(UUID.randomUUID(), "game-master", gameId))
                .isInstanceOf(GameAccessDeniedException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void cannotRaiseClosedGame() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = raisableGame(masterId, Instant.now().minus(2, ChronoUnit.DAYS));
        game.setStatus(GameStatus.CLOSED);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().raise(masterId, "game-master", gameId))
                .isInstanceOf(GameCannotBeRaisedException.class);
    }

    @Test
    void masterEditsOwnGame() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UpdateGameRequest request = withTitle(updateRequest(), "Новое название");
        GameResponse response = service().update(masterId, gameId, request);

        assertThat(response.title()).isEqualTo("Новое название");
        // Владение и статус редактированием не управляются.
        assertThat(response.masterId()).isEqualTo(masterId);
        assertThat(response.status()).isEqualTo(GameStatus.OPEN);
    }

    @Test
    void strangerCannotEditGame() {
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, UUID.randomUUID());
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().update(UUID.randomUUID(), gameId, updateRequest()))
                .isInstanceOf(GameAccessDeniedException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void editKeepsTheSameChecksAsCreation() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        lenient().when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service().update(
                masterId, gameId, withPlayers(updateRequest(), 6, 5)))
                .isInstanceOf(InvalidPlayerCountException.class);

        assertThatThrownBy(() -> service().update(
                masterId, gameId, withCity(updateRequest(), "Кишинёв")))
                .isInstanceOf(InvalidGameDetailsException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void switchingToPrivateIssuesInviteCode() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GameResponse response = service().update(
                masterId, gameId, withVisibility(updateRequest(), GameVisibility.PRIVATE));

        assertThat(response.inviteCode()).isNotNull();
    }

    @Test
    void switchingBackToPublicClearsInviteCode() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        game.setVisibility(GameVisibility.PRIVATE);
        game.setInviteCode(UUID.randomUUID());
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Оставленный код открывал бы прямой доступ и после возврата в публичные.
        GameResponse response = service().update(masterId, gameId, updateRequest());

        assertThat(response.inviteCode()).isNull();
    }

    @Test
    void typoInTitleIsFixableEvenWithSessionsAndApprovedPlayers() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        game.setTitle("Проклятье Сирада");
        GameSession session = mock(GameSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId))
                .thenReturn(List.of(session));
        when(registrationRepository.countBySessionIdAndStatus(
                sessionId, SessionRegistrationStatus.APPROVED)).thenReturn(4L);
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Замок стоит только на платности: опечатку в названии мастер обязан
        // мочь исправить в любой момент, даже когда игроки уже набраны.
        GameResponse response = service().update(
                masterId, gameId, withTitle(updateRequest(), "Проклятие Страда"));

        assertThat(response.title()).isEqualTo("Проклятие Страда");
    }

    @Test
    void costTypeCannotChangeWhenSessionsExist() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.existsByGameId(gameId)).thenReturn(true);

        // У сессий бесплатной игры нет ни суммы, ни условий оплаты — задним
        // числом сделать игру платной нечем.
        assertThatThrownBy(() -> service().update(
                masterId, gameId, withCostType(updateRequest(), GameCostType.PAID)))
                .isInstanceOf(InvalidGameDetailsException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void costTypeChangesFreelyWhileThereAreNoSessions() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.existsByGameId(gameId)).thenReturn(false);
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GameResponse response = service().update(
                masterId, gameId, withCostType(updateRequest(), GameCostType.PAID));

        assertThat(response.costType()).isEqualTo(GameCostType.PAID);
    }

    @Test
    void maxPlayersCannotDropBelowApprovedPlayers() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        GameSession session = mock(GameSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId))
                .thenReturn(List.of(session));
        when(registrationRepository.countBySessionIdAndStatus(
                sessionId, SessionRegistrationStatus.APPROVED)).thenReturn(4L);

        // Иначе принятые игроки оказались бы сверх лимита, а починить это нечем.
        assertThatThrownBy(() -> service().update(
                masterId, gameId, withPlayers(updateRequest(), 2, 3)))
                .isInstanceOf(InvalidPlayerCountException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void maxPlayersGrowsFreely() {
        UUID masterId = UUID.randomUUID();
        UUID gameId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        Game game = editableGame(gameId, masterId);
        GameSession session = mock(GameSession.class);
        when(session.getId()).thenReturn(sessionId);
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.of(game));
        when(sessionRepository.findAllByGameIdOrderByStartsAtAsc(gameId))
                .thenReturn(List.of(session));
        when(registrationRepository.countBySessionIdAndStatus(
                sessionId, SessionRegistrationStatus.APPROVED)).thenReturn(4L);
        when(repository.save(any(Game.class))).thenAnswer(invocation -> invocation.getArgument(0));

        GameResponse response = service().update(
                masterId, gameId, withPlayers(updateRequest(), 3, 8));

        assertThat(response.maxPlayers()).isEqualTo(8);
    }

    @Test
    void editingMissingGameIsNotFound() {
        UUID gameId = UUID.randomUUID();
        when(repository.findByIdForUpdate(gameId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().update(UUID.randomUUID(), gameId, updateRequest()))
                .isInstanceOf(GameNotFoundException.class);
    }

    private UpdateGameRequest withTitle(UpdateGameRequest source, String title) {
        return new UpdateGameRequest(
                title, source.system(), source.imageUrl(), source.virtualTableUrl(), source.genre(),
                source.description(), source.requirements(), source.allowedSources(), source.type(),
                source.city(), source.playersToStart(), source.maxPlayers(), source.minAge(),
                source.maxAge(), source.startingLevel(), source.crossplayAllowed(),
                source.durationType(), source.costType(), source.visibility());
    }

    private UpdateGameRequest withPlayers(
            UpdateGameRequest source, int playersToStart, int maxPlayers) {
        return new UpdateGameRequest(
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(),
                source.genre(), source.description(), source.requirements(), source.allowedSources(),
                source.type(), source.city(), playersToStart, maxPlayers, source.minAge(),
                source.maxAge(), source.startingLevel(), source.crossplayAllowed(),
                source.durationType(), source.costType(), source.visibility());
    }

    private UpdateGameRequest withCity(UpdateGameRequest source, String city) {
        return new UpdateGameRequest(
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(),
                source.genre(), source.description(), source.requirements(), source.allowedSources(),
                source.type(), city, source.playersToStart(), source.maxPlayers(), source.minAge(),
                source.maxAge(), source.startingLevel(), source.crossplayAllowed(),
                source.durationType(), source.costType(), source.visibility());
    }

    private UpdateGameRequest withCostType(UpdateGameRequest source, GameCostType costType) {
        return new UpdateGameRequest(
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(),
                source.genre(), source.description(), source.requirements(), source.allowedSources(),
                source.type(), source.city(), source.playersToStart(), source.maxPlayers(),
                source.minAge(), source.maxAge(), source.startingLevel(), source.crossplayAllowed(),
                source.durationType(), costType, source.visibility());
    }

    private UpdateGameRequest withVisibility(
            UpdateGameRequest source, GameVisibility visibility) {
        return new UpdateGameRequest(
                source.title(), source.system(), source.imageUrl(), source.virtualTableUrl(),
                source.genre(), source.description(), source.requirements(), source.allowedSources(),
                source.type(), source.city(), source.playersToStart(), source.maxPlayers(),
                source.minAge(), source.maxAge(), source.startingLevel(), source.crossplayAllowed(),
                source.durationType(), source.costType(), visibility);
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
