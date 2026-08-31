package club.ttg.findgame.notification;

import java.util.UUID;

public class NotificationNotFoundException extends RuntimeException {

    public NotificationNotFoundException(UUID id) {
        super("Уведомление %s не найдено".formatted(id));
    }
}
