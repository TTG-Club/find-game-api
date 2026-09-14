package club.ttg.findgame.game;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GameSystemRepository extends JpaRepository<GameSystem, String> {

    List<GameSystem> findAllByOrderByNameAsc();
}
