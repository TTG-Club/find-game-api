package club.ttg.findgame.notification;

import club.ttg.findgame.notification.api.NotificationResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Лента уведомлений участника поиска игр.
 *
 * Своя, а не общая с уведомлениями сайта: события здесь свои, живут в этой же
 * базе рядом с играми и сессиями, и связывать ради них два сервиса значило бы
 * платить межсервисным вызовом за каждую поданную заявку.
 */
@Service
public class NotificationService {

    private final NotificationRepository repository;

    public NotificationService(NotificationRepository repository) {
        this.repository = repository;
    }

    /**
     * Кладёт уведомление в ленту получателя. Себе уведомления не приходят:
     * мастер и так знает, что он сам сделал.
     */
    @Transactional
    public void notifyUser(
            UUID recipientId,
            UUID actorId,
            NotificationType type,
            UUID gameId,
            String gameTitle,
            UUID sessionId,
            String sessionTitle
    ) {
        if (recipientId == null || recipientId.equals(actorId)) {
            return;
        }

        repository.save(Notification.of(
                recipientId, type, gameId, gameTitle, sessionId, sessionTitle));
    }

    /** То же самое сразу нескольким игрокам. */
    @Transactional
    public void notifyUsers(
            Collection<UUID> recipientIds,
            UUID actorId,
            NotificationType type,
            UUID gameId,
            String gameTitle,
            UUID sessionId,
            String sessionTitle
    ) {
        List<Notification> notifications = recipientIds.stream()
                .filter(recipientId -> recipientId != null && !recipientId.equals(actorId))
                .map(recipientId -> Notification.of(
                        recipientId, type, gameId, gameTitle, sessionId, sessionTitle))
                .toList();

        if (!notifications.isEmpty()) {
            repository.saveAll(notifications);
        }
    }

    @Transactional(readOnly = true)
    public Page<NotificationResponse> find(UUID recipientId, int page, int size) {
        return repository
                .findAllByRecipientIdOrderByCreatedAtDesc(recipientId, PageRequest.of(page, size))
                .map(NotificationService::toResponse);
    }

    @Transactional(readOnly = true)
    public long countUnread(UUID recipientId) {
        return repository.countByRecipientIdAndReadAtIsNull(recipientId);
    }

    /**
     * Отмечает уведомление прочитанным. Чужое не найдётся: выборка идёт по
     * паре «идентификатор + получатель».
     */
    @Transactional
    public NotificationResponse markRead(UUID recipientId, UUID notificationId) {
        Notification notification = repository
                .findByIdAndRecipientId(notificationId, recipientId)
                .orElseThrow(() -> new NotificationNotFoundException(notificationId));

        if (notification.getReadAt() == null) {
            notification.setReadAt(Instant.now());
            repository.save(notification);
        }

        return toResponse(notification);
    }

    @Transactional
    public void markAllRead(UUID recipientId) {
        repository.markAllRead(recipientId, Instant.now());
    }

    private static NotificationResponse toResponse(Notification notification) {
        return new NotificationResponse(
                notification.getId(),
                notification.getType(),
                notification.getGameId(),
                notification.getGameTitle(),
                notification.getSessionId(),
                notification.getSessionTitle(),
                notification.getReadAt(),
                notification.getCreatedAt());
    }
}
