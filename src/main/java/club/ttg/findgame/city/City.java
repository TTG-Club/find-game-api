package club.ttg.findgame.city;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Город из справочника.
 *
 * Игра хранит город строкой — справочник нужен затем, чтобы мастера писали
 * «Санкт-Петербург» одинаково: иначе фильтр каталога рассыпается на десяток
 * почти одинаковых значений.
 */
@Entity
@Table(name = "cities")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class City {

    @Id
    private UUID id;

    @Column(nullable = false, length = 120)
    private String name;

    /** Область или штат: две Ростова и три Владимира иначе не различить. */
    @Column(length = 120)
    private String region;

    @Column(nullable = false, length = 120)
    private String country;

    /** Порядок подсказок: крупные города выше. */
    @Column
    private Integer population;

    @PrePersist
    void prePersist() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
