package club.ttg.findgame.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    private static final UUID GAME_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();

    @Mock
    private NotificationRepository repository;

    @Test
    void keepsGameAndSessionTitlesInTheNotification() {
        UUID recipientId = UUID.randomUUID();

        service().notifyUser(
                recipientId, UUID.randomUUID(), NotificationType.REGISTRATION_SUBMITTED,
                GAME_ID, "Проклятие Страда", SESSION_ID, "Знакомство с Баровией");

        // Названия хранятся копией: лента должна читаться и после того, как
        // игру переименуют или удалят.
        ArgumentCaptor<Notification> saved = ArgumentCaptor.forClass(Notification.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getRecipientId()).isEqualTo(recipientId);
        assertThat(saved.getValue().getGameTitle()).isEqualTo("Проклятие Страда");
        assertThat(saved.getValue().getSessionTitle()).isEqualTo("Знакомство с Баровией");
        assertThat(saved.getValue().getReadAt()).isNull();
    }

    @Test
    void doesNotNotifyTheOneWhoCausedTheEvent() {
        UUID masterId = UUID.randomUUID();

        // Мастер и так знает, что он сам сделал.
        service().notifyUser(
                masterId, masterId, NotificationType.SESSION_STARTED,
                GAME_ID, "Игра", SESSION_ID, "Сессия");

        verify(repository, never()).save(any());
    }

    @Test
    void notifiesEveryPlayerExceptTheActor() {
        UUID masterId = UUID.randomUUID();
        UUID firstPlayer = UUID.randomUUID();
        UUID secondPlayer = UUID.randomUUID();

        service().notifyUsers(
                Arrays.asList(firstPlayer, masterId, secondPlayer, null),
                masterId, NotificationType.SESSION_COMPLETED,
                GAME_ID, "Игра", SESSION_ID, "Сессия");

        ArgumentCaptor<List<Notification>> saved = ArgumentCaptor.captor();
        verify(repository).saveAll(saved.capture());
        assertThat(saved.getValue())
                .extracting(Notification::getRecipientId)
                .containsExactly(firstPlayer, secondPlayer);
    }

    @Test
    void savesNothingWhenThereIsNobodyToNotify() {
        UUID masterId = UUID.randomUUID();

        service().notifyUsers(
                List.of(masterId), masterId, NotificationType.SESSION_STARTED,
                GAME_ID, "Игра", SESSION_ID, "Сессия");

        verify(repository, never()).saveAll(any());
    }

    @Test
    void marksNotificationReadOnlyOnce() {
        UUID recipientId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        Notification notification = Notification.of(
                recipientId, NotificationType.REGISTRATION_APPROVED,
                GAME_ID, "Игра", SESSION_ID, "Сессия");
        when(repository.findByIdAndRecipientId(notificationId, recipientId))
                .thenReturn(Optional.of(notification));
        when(repository.save(any(Notification.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service().markRead(recipientId, notificationId);
        service().markRead(recipientId, notificationId);

        // Повторная отметка не двигает время прочтения.
        verify(repository).save(any(Notification.class));
    }

    @Test
    void doesNotOpenSomeoneElseNotification() {
        UUID recipientId = UUID.randomUUID();
        UUID notificationId = UUID.randomUUID();
        when(repository.findByIdAndRecipientId(notificationId, recipientId))
                .thenReturn(Optional.empty());

        // Чужое уведомление для пользователя просто не существует.
        assertThatThrownBy(() -> service().markRead(recipientId, notificationId))
                .isInstanceOf(NotificationNotFoundException.class);
    }

    private NotificationService service() {
        return new NotificationService(repository);
    }
}
