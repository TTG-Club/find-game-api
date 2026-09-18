package club.ttg.findgame.publications;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Контракты настройки и безопасного просмотра публикаций. */
public final class PublicationModels {
    private PublicationModels() {}

    public enum Platform { DISCORD, TELEGRAM }

    public record Slot(@Min(1) @Max(7) int day,
                       @NotNull @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]") String time) {}
    public record SettingsInput(boolean enabled, @NotNull @Size(max = 14) List<@NotNull @Valid Slot> schedule,
                                @Min(0) long revision) {}
    public record ChannelInput(@NotBlank @Size(max = 100) String name, boolean enabled,
                               @Size(max = 512) String webhookUrl,
                               @Size(min = 1, max = 14) List<@NotNull @Valid Slot> schedule,
                               @Min(0) long revision, @Size(max = 32) String telegramChatId, Platform platform) {}
    public record Settings(boolean enabled, List<Slot> schedule, long revision, Instant pausedUntil) {}
    public record Channel(UUID id, String name, boolean enabled, List<Slot> schedule,
                          long revision, Instant nextRunAt, Platform platform) {}
    public record Overview(Settings settings, List<Channel> channels, boolean configured, String timeZone,
                           boolean telegramConfigured) {}
    public record TestResult(String status, String detail) {}
    /** Поле description возвращается пустым для совместимости с прежней схемой фронтенда. */
    public record GameEntry(UUID id, String title, String system, int takenSeats, int maxPlayers,
                            String url, String genreSummary, String description) {}
    public record Run(UUID id, String channelName, Instant scheduledAt, String status,
                      int gameCount, String detail, String messageId, Platform platform) {}
    record StoredChannel(Channel channel, String secret, String fingerprint) {}
    record Delivery(UUID runId, UUID channelId, long channelRevision, String secret,
                    Instant scheduledAt, int attempts, Platform platform) {}
    record Outcome(String status, String detail, String messageId, Instant retryAt) {}
    record DigestMessage(Map<String, Object> payload, int gameCount) {}
}
