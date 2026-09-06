package club.ttg.findgame.follow;

import club.ttg.findgame.follow.api.FollowResponse;
import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameAccessDeniedException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.game.GameStatus;
import club.ttg.findgame.game.GameVisibility;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FollowServiceTest {

    private static final UUID MASTER_ID = UUID.randomUUID();
    private static final UUID PLAYER_ID = UUID.randomUUID();
    private static final UUID GAME_ID = UUID.randomUUID();

    @Mock
    private UserFollowRepository repository;

    @Mock
    private GameRepository gameRepository;

    @Mock
    private GameRegistrationRepository registrationRepository;

    @Mock
    private NotificationService notificationService;

    @Test
    void playerMarksMasterWithoutAsking() {
        // Отметка односторонняя: это закладка в своём списке, а не дружба.
        service().followMaster(PLAYER_ID, MASTER_ID);

        ArgumentCaptor<UserFollow> saved = ArgumentCaptor.forClass(UserFollow.class);

        verify(repository).save(saved.capture());

        assertThat(saved.getValue().getOwnerId()).isEqualTo(PLAYER_ID);
        assertThat(saved.getValue().getTargetId()).isEqualTo(MASTER_ID);
        assertThat(saved.getValue().getKind()).isEqualTo(FollowKind.MASTER_FOLLOW);
    }

    @Test
    void repeatedMarkChangesNothing() {
        when(repository.existsByOwnerIdAndTargetIdAndKind(
                PLAYER_ID, MASTER_ID, FollowKind.MASTER_FOLLOW)).thenReturn(true);

        service().followMaster(PLAYER_ID, MASTER_ID);

        verify(repository, never()).save(any());
    }

    @Test
    void nobodyMarksThemselves() {
        assertThatThrownBy(() -> service().followMaster(PLAYER_ID, PLAYER_ID))
                .isInstanceOf(FollowNotAllowedException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void masterMarksPlayerWhoAppliedToHisGame() {
        when(registrationRepository.existsAtMasterGames(
                MASTER_ID, PLAYER_ID, RegistrationStatus.REJECTED)).thenReturn(true);

        service().bookmarkPlayer(MASTER_ID, PLAYER_ID);

        ArgumentCaptor<UserFollow> saved = ArgumentCaptor.forClass(UserFollow.class);

        verify(repository).save(saved.capture());

        assertThat(saved.getValue().getKind()).isEqualTo(FollowKind.PLAYER_BOOKMARK);
    }

    @Test
    void strangerIsNotMarkedAsPlayer() {
        when(registrationRepository.existsAtMasterGames(
                MASTER_ID, PLAYER_ID, RegistrationStatus.REJECTED)).thenReturn(false);

        // Иначе отметка стала бы способом позвать незнакомого человека по
        // одному лишь идентификатору.
        assertThatThrownBy(() -> service().bookmarkPlayer(MASTER_ID, PLAYER_ID))
                .isInstanceOf(FollowNotAllowedException.class);

        verify(repository, never()).save(any());
    }

    @Test
    void markedListComesNewestFirst() {
        UserFollow follow = UserFollow.of(PLAYER_ID, MASTER_ID, FollowKind.MASTER_FOLLOW);

        follow.setCreatedAt(Instant.parse("2026-09-01T10:00:00Z"));

        when(repository.findAllByOwnerIdAndKindOrderByCreatedAtDesc(
                PLAYER_ID, FollowKind.MASTER_FOLLOW)).thenReturn(List.of(follow));

        List<FollowResponse> masters = service().findFollowedMasters(PLAYER_ID);

        assertThat(masters).singleElement()
                .satisfies(item -> assertThat(item.userId()).isEqualTo(MASTER_ID));
    }

    @Test
    void newPublicGameReachesFollowers() {
        UserFollow follow = UserFollow.of(PLAYER_ID, MASTER_ID, FollowKind.MASTER_FOLLOW);

        when(repository.findAllByTargetIdAndKind(MASTER_ID, FollowKind.MASTER_FOLLOW))
                .thenReturn(List.of(follow));

        service().announceGame(game(GameVisibility.PUBLIC, GameStatus.OPEN, false));

        verify(notificationService).notifyUsers(
                eq(List.of(PLAYER_ID)),
                eq(MASTER_ID),
                eq(NotificationType.MASTER_PUBLISHED_GAME),
                eq(GAME_ID),
                eq("Проклятие Страда"),
                eq(null),
                eq(null));
    }

    @Test
    void privateGameStaysUnannounced() {
        // Приватную игру показывают по ссылке, и отметка — не повод её
        // раскрывать.
        service().announceGame(game(GameVisibility.PRIVATE, GameStatus.OPEN, false));

        verify(notificationService, never())
                .notifyUsers(anyCollection(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void masterInvitesMarkedPlayer() {
        stubOpenGame();
        stubBookmark(true);

        when(registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                GAME_ID, PLAYER_ID, RegistrationStatus.REJECTED)).thenReturn(false);

        when(notificationService.wasNotified(
                PLAYER_ID, NotificationType.GAME_INVITE, GAME_ID)).thenReturn(false);

        service().invitePlayer(MASTER_ID, GAME_ID, PLAYER_ID);

        // Приглашение — уведомление со ссылкой, а не место в составе: заявку
        // игрок подаёт сам.
        verify(notificationService).notifyUser(
                PLAYER_ID,
                MASTER_ID,
                NotificationType.GAME_INVITE,
                GAME_ID,
                "Проклятие Страда",
                null,
                null);
    }

    @Test
    void strangerIsNotInvited() {
        stubOpenGame();
        stubBookmark(false);

        assertThatThrownBy(() -> service().invitePlayer(MASTER_ID, GAME_ID, PLAYER_ID))
                .isInstanceOf(FollowNotAllowedException.class);
    }

    @Test
    void foreignGameIsNotUsedForInvites() {
        Game game = game(GameVisibility.PUBLIC, GameStatus.OPEN, false);

        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.of(game));

        assertThatThrownBy(() -> service()
                .invitePlayer(UUID.randomUUID(), GAME_ID, PLAYER_ID))
                .isInstanceOf(GameAccessDeniedException.class);
    }

    @Test
    void closedRecruitmentTakesNobody() {
        Game game = game(GameVisibility.PUBLIC, GameStatus.OPEN, true);

        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.of(game));
        stubBookmark(true);

        assertThatThrownBy(() -> service().invitePlayer(MASTER_ID, GAME_ID, PLAYER_ID))
                .isInstanceOf(FollowNotAllowedException.class);

        verify(notificationService, never())
                .notifyUser(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void alreadyAppliedPlayerIsNotInvited() {
        stubOpenGame();
        stubBookmark(true);

        when(registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                GAME_ID, PLAYER_ID, RegistrationStatus.REJECTED)).thenReturn(true);

        assertThatThrownBy(() -> service().invitePlayer(MASTER_ID, GAME_ID, PLAYER_ID))
                .isInstanceOf(FollowNotAllowedException.class);
    }

    @Test
    void inviteIsNotRepeated() {
        stubOpenGame();
        stubBookmark(true);

        when(registrationRepository.existsByGameIdAndPlayerIdAndStatusNot(
                GAME_ID, PLAYER_ID, RegistrationStatus.REJECTED)).thenReturn(false);

        when(notificationService.wasNotified(
                PLAYER_ID, NotificationType.GAME_INVITE, GAME_ID)).thenReturn(true);

        // Второй раз зовут, только надоедая.
        assertThatThrownBy(() -> service().invitePlayer(MASTER_ID, GAME_ID, PLAYER_ID))
                .isInstanceOf(FollowNotAllowedException.class);

        verify(notificationService, never())
                .notifyUser(any(), any(), any(), any(), any(), any(), any());
    }

    private FollowService service() {
        return new FollowService(
                repository, gameRepository, registrationRepository, notificationService);
    }

    private void stubOpenGame() {
        Game game = game(GameVisibility.PUBLIC, GameStatus.OPEN, false);

        when(gameRepository.findByIdAndDeletedAtIsNull(GAME_ID)).thenReturn(Optional.of(game));
    }

    private void stubBookmark(boolean bookmarked) {
        when(repository.existsByOwnerIdAndTargetIdAndKind(
                MASTER_ID, PLAYER_ID, FollowKind.PLAYER_BOOKMARK)).thenReturn(bookmarked);
    }

    /** Игра мастера в заданном состоянии. */
    private static Game game(GameVisibility visibility, GameStatus status, boolean closed) {
        Game game = mock(Game.class);

        lenient().when(game.getId()).thenReturn(GAME_ID);
        lenient().when(game.getMasterId()).thenReturn(MASTER_ID);
        lenient().when(game.getTitle()).thenReturn("Проклятие Страда");
        lenient().when(game.getVisibility()).thenReturn(visibility);
        lenient().when(game.getStatus()).thenReturn(status);
        lenient().when(game.isRecruitmentClosed()).thenReturn(closed);

        return game;
    }
}
