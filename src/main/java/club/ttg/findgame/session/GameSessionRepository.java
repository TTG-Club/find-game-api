package club.ttg.findgame.session;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface GameSessionRepository extends JpaRepository<GameSession, UUID> {

    List<GameSession> findAllByGameIdOrderByStartsAtAsc(UUID gameId);

    Optional<GameSession> findByIdAndGameId(UUID id, UUID gameId);
}
