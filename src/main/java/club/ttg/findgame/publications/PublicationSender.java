package club.ttg.findgame.publications;

import org.springframework.stereotype.Component;
import java.util.Map;
import static club.ttg.findgame.publications.PublicationModels.*;

/** Выбирает транспорт канала и раскрывает адрес только перед отправкой. */
@Component
public class PublicationSender {
    private final PublicationSecrets secrets;
    private final DiscordWebhookClient discord;
    private final TelegramBotClient telegram;
    private final VkWallClient vk;

    /** Подключает независимые клиенты и общее хранилище секретов. */
    public PublicationSender(PublicationSecrets secrets, DiscordWebhookClient discord, TelegramBotClient telegram, VkWallClient vk) {
        this.secrets = secrets; this.discord = discord; this.telegram = telegram; this.vk = vk;
    }

    /** Проверяет только готовность выбранной платформы. */
    boolean configured(Platform platform) {
        return secrets.configured() && switch (platform) {
            case DISCORD -> true;
            case TELEGRAM -> telegram.configured();
            case VK -> vk.configured();
        };
    }

    /** Адрес из настроек сервера для канала без своего адреса; есть только у VK. */
    String defaultAddress(Platform platform) {
        return platform == Platform.VK ? vk.defaultGroupId() : "";
    }

    /** Никогда не передаёт адрес канала транспорту другой платформы. */
    Outcome send(Delivery delivery, Map<String, Object> payload) {
        String destination = secrets.decrypt(delivery.platform(), delivery.secret());
        return switch (delivery.platform()) {
            case DISCORD -> discord.send(destination, payload);
            case TELEGRAM -> telegram.send(destination, payload);
            case VK -> vk.send(destination, payload);
        };
    }
}
