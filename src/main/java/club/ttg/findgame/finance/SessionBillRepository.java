package club.ttg.findgame.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionBillRepository extends JpaRepository<SessionBill, UUID> {
    Optional<SessionBill> findBySessionIdAndPlayerId(UUID sessionId, UUID playerId);
    List<SessionBill> findAllByGameIdOrderByCreatedAtAsc(UUID gameId);
    List<SessionBill> findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(UUID gameId, UUID playerId);
    List<SessionBill> findAllBySessionId(UUID sessionId);
}
