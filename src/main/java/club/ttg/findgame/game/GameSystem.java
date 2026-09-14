package club.ttg.findgame.game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Игровая система из управляемого справочника. */
@Entity
@Table(name = "game_systems")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GameSystem {

    /** Своя система: её название игра хранит сама, в {@code customSystem}. */
    public static final String HOMEBREW = "HOMEBREW";

    @Id
    @Column(length = 30)
    private String code;

    @Column(nullable = false, unique = true, length = 120)
    private String name;

    public GameSystem(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
