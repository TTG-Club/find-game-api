package club.ttg.findgame.game;

import club.ttg.findgame.config.GameInactivityProperties;
import club.ttg.findgame.notification.NotificationService;
import club.ttg.findgame.notification.NotificationType;
import club.ttg.findgame.registration.GameRegistration;
import club.ttg.findgame.registration.GameRegistrationRepository;
import club.ttg.findgame.registration.RegistrationStatus;
import club.ttg.findgame.session.GameSessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GameInactivityServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-27T12:00:00Z");

    @Mock
    private GameRepository repository;

    @Mock
    private GameSessionRepository sessionRepository;

    @Mock
    private GameRegistrationRepository registrationRepository;

    @Mock
    private NotificationService notificationService;

    @Test
    void asksMasterAfterThirtyDaysWithoutActivity() {
        Game game = openGame(NOW.minus(Duration.ofDays(31)));
        when(repository.findByIdForUpdate(game.getId())).thenReturn(Optional.of(game));
        when(sessionRepository.existsOngoingOrUpcoming(game.getId(), NOW)).thenReturn(false);

        Optional<GameInactivityService.InactivityWarning> warning = service().warn(game.getId(), NOW);

        Instant cancelAt = NOW.plus(Duration.ofDays(3));
        assertThat(warning).contains(new GameInactivityService.InactivityWarning(
                game.getMasterId(), game.getId(), game.getTitle(), cancelAt));
        assertThat(game.getAutoCancelAt()).isEqualTo(cancelAt);
        assertThat(game.getStatus()).isEqualTo(GameStatus.OPEN);
        verify(notificationService).notifyUser(
                eq(game.getMasterId()), isNull(), eq(NotificationType.GAME_INACTIVITY_WARNING),
                eq(game.getId()), eq(game.getTitle()), isNull(), isNull(), eq(cancelAt.toString()));
    }

    @Test
    void doesNotAskWhenSessionIsUpcoming() {
        Game game = openGame(NOW.minus(Duration.ofDays(60)));
        when(repository.findByIdForUpdate(game.getId())).thenReturn(Optional.of(game));
        when(sessionRepository.existsOngoingOrUpcoming(game.getId(), NOW)).thenReturn(true);

        assertThat(service().warn(game.getId(), NOW)).isEmpty();
        assertThat(game.getAutoCancelAt()).isNull();
        verify(notificationService, never()).notifyUser(
                any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotAskTwice() {
        Game game = openGame(NOW.minus(Duration.ofDays(31)));
        Instant cancelAt = NOW.plus(Duration.ofDays(1));
        game.setAutoCancelAt(cancelAt);
        when(repository.findByIdForUpdate(game.getId())).thenReturn(Optional.of(game));

        assertThat(service().warn(game.getId(), NOW)).isEmpty();
        assertThat(game.getAutoCancelAt()).isEqualTo(cancelAt);
    }

    @Test
    void doesNotAskWhenMasterWasActiveRecently() {
        Game game = openGame(NOW.minus(Duration.ofDays(29)));
        when(repository.findByIdForUpdate(game.getId())).thenReturn(Optional.of(game));

        assertThat(service().warn(game.getId(), NOW)).isEmpty();
    }

    @Test
    void cancelsGameWhenMasterDidNotAnswer() {
        Game game = openGame(NOW.minus(Duration.ofDays(34)));
        game.setAutoCancelAt(NOW.minusSeconds(1));
        UUID playerId = UUID.randomUUID();
        GameRegistration registration = mock(GameRegistration.class);
        when(registration.getPlayerId()).thenReturn(playerId);
        when(repository.findByIdForUpdate(game.getId())).thenReturn(Optional.of(game));
        when(registrationRepository.findAllByGameIdAndStatus(game.getId(), RegistrationStatus.APPROVED))
                .thenReturn(List.of(registration));

        assertThat(service().cancel(game.getId(), NOW)).isTrue();

        assertThat(game.getStatus()).isEqualTo(GameStatus.CANCELLED);
        assertThat(game.getAutoCancelAt()).isNull();
        verify(notificationService).notifyUsers(
                eq(List.of(playerId)), eq(game.getMasterId()), eq(NotificationType.GAME_CANCELLED),
                eq(game.getId()), eq(game.getTitle()), isNull(), isNull());
        verify(notificationService).notifyUser(
                eq(game.getMasterId()), isNull(), eq(NotificationType.GAME_CANCELLED_FOR_INACTIVITY),
                eq(game.getId()), eq(game.getTitle()), isNull(), isNull());
    }

    @Test
    void keepsGameWhenMasterAnswered() {
        Game game = openGame(NOW.minus(Duration.ofDays(34)));
        game.setAutoCancelAt(NOW.minusSeconds(1));
        game.markActive(NOW.minusSeconds(10));
        when(repository.findByIdForUpdate(game.getId())).thenReturn(Optional.of(game));

        assertThat(service().cancel(game.getId(), NOW)).isFalse();

        assertThat(game.getStatus()).isEqualTo(GameStatus.OPEN);
        verify(notificationService, never()).notifyUsers(anyCollection(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void doesNotCancelBeforeDeadline() {
        Game game = openGame(NOW.minus(Duration.ofDays(32)));
        game.setAutoCancelAt(NOW.plusSeconds(60));
        when(repository.findByIdForUpdate(game.getId())).thenReturn(Optional.of(game));

        assertThat(service().cancel(game.getId(), NOW)).isFalse();
        assertThat(game.getStatus()).isEqualTo(GameStatus.OPEN);
    }

    @Test
    void markActiveClearsQuestion() {
        Game game = openGame(NOW.minus(Duration.ofDays(31)));
        game.setAutoCancelAt(NOW.plus(Duration.ofDays(2)));

        game.markActive(NOW);

        assertThat(game.getLastActivityAt()).isEqualTo(NOW);
        assertThat(game.getAutoCancelAt()).isNull();
    }

    private GameInactivityService service() {
        return new GameInactivityService(
                repository, sessionRepository, registrationRepository, notificationService,
                new GameInactivityProperties());
    }

    private static Game openGame(Instant lastActivityAt) {
        Game game = new Game();

        game.setId(UUID.randomUUID());
        game.setMasterId(UUID.randomUUID());
        game.setTitle("Проклятие Страда");
        game.setStatus(GameStatus.OPEN);
        game.setLastActivityAt(lastActivityAt);

        return game;
    }
}
