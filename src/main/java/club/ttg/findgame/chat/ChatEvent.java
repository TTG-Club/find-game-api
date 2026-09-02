package club.ttg.findgame.chat;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "chat_events")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ChatEvent {

    @Id
    private UUID id;

    /** Комната, в которой сказано. */
    @Column(name = "nexus_id", nullable = false)
    private UUID nexusId;

    /**
     * Игра, сессия и собеседник прежней модели чата. Новые события их не
     * заполняют: чат живёт в комнате. Колонки остались ради истории, которая
     * в комнату не переехала, — личной переписки мастера с игроком.
     */
    @Column(name = "game_id")
    private UUID gameId;

    @Column(name = "session_id")
    private UUID sessionId;

    @Column(name = "player_id")
    private UUID playerId;

    @Column(name = "author_id", nullable = false)
    private UUID authorId;

    @Column(name = "client_message_id", nullable = false)
    private UUID clientMessageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 30)
    private ChatEventType type;

    @Column(columnDefinition = "text")
    private String content;

    /**
     * Содержимое броска — готовый JSON строкой.
     *
     * Не `JsonNode`: узел Jackson 3 хранилище сериализовать не умеет — его
     * форматтер работает с Jackson 2, — и запись с непустым содержимым
     * падала. Строку оно кладёт в `jsonb` без посредников.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
