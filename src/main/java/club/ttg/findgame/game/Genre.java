package club.ttg.findgame.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.Locale;
import java.util.UUID;

/**
 * Жанр из готового списка. Список ведётся миграциями: мастер его не пополняет,
 * а жанр, которого в нём нет, пишет в игре отдельным полем.
 */
@Entity
@Table(name = "genres")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Genre {

    /**
     * Значение отбора «свой жанр». В справочнике такой строки нет: свой жанр
     * игра хранит сама, в {@code customGenre}.
     */
    public static final String HOMEBREW = "HOMEBREW";

    @Id
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "normalized_name", nullable = false, unique = true, length = 100)
    private String normalizedName;

    public Genre(String name) {
        this.name = name.strip();
        this.normalizedName = normalize(name);
    }

    public static String normalize(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
