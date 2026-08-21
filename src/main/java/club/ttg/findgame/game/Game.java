package club.ttg.findgame.game;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "games")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Game {

    @Id
    private UUID id;

    @Column(name = "master_id", nullable = false)
    private UUID masterId;

    @Column(nullable = false, length = 150)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "game_system", nullable = false, length = 30)
    private GameSystem system;

    @Column(name = "image_url", length = 2048)
    private String imageUrl;

    @Column(name = "virtual_table_url", length = 2048)
    private String virtualTableUrl;

    @Column(length = 100)
    private String genre;

    @Column(nullable = false, columnDefinition = "text")
    private String description;

    @Column(nullable = false, columnDefinition = "text")
    private String requirements;

    @ElementCollection
    @CollectionTable(name = "game_allowed_sources", joinColumns = @JoinColumn(name = "game_id"))
    @Column(name = "source", nullable = false, length = 120)
    private Set<String> allowedSources = new LinkedHashSet<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "game_type", nullable = false, length = 20)
    private GameType type;

    @Column(length = 120)
    private String city;

    @Column(name = "players_to_start", nullable = false)
    private int playersToStart;

    @Column(name = "max_players", nullable = false)
    private int maxPlayers;

    @Column(name = "min_age")
    private Integer minAge;

    @Column(name = "max_age")
    private Integer maxAge;

    @Column(name = "starting_level", nullable = false)
    private int startingLevel;

    @Column(name = "crossplay_allowed", nullable = false)
    private boolean crossplayAllowed;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private GameStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "duration_type", nullable = false, length = 20)
    private GameDurationType durationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "cost_type", nullable = false, length = 20)
    private GameCostType costType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GameVisibility visibility;

    @Column(name = "invite_code", unique = true)
    private UUID inviteCode;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "deletion_reason", length = 1000)
    private String deletionReason;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public void setAllowedSources(Set<String> allowedSources) {
        this.allowedSources = allowedSources == null ? new LinkedHashSet<>() : new LinkedHashSet<>(allowedSources);
    }
}
