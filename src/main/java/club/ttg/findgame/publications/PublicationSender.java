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

    /** Подключает независимые клиенты и общее хранилище секретов. */
    public PublicationSender(PublicationSecrets secrets, DiscordWebhookClient discord, TelegramBotClient telegram) {
        this.secrets = secrets; this.discord = discord; this.telegram = telegram;
    }

    /** Проверяет только готовность выбранной платформы. */
    boolean configured(Platform platform) {
        return secrets.configured() && (platform == Platform.DISCORD || telegram.configured());
    }

    /** Никогда не передаёт адрес канала транспорту другой платформы. */
    Outcome send(Delivery delivery, Map<String, Object> payload) {
        String destination = secrets.decrypt(delivery.platform(), delivery.secret());
        return delivery.platform() == Platform.TELEGRAM ? telegram.send(destination, payload) : discord.send(destination, payload);
    }
}
