package club.ttg.findgame.game;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Column;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Entity
@Table(name = "game_creation_locks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
class GameCreationLock {

    @Id
    @Column(name = "master_id")
    private UUID masterId;
}
