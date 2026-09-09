package club.ttg.findgame.finance;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface FinanceEntryRepository extends JpaRepository<FinanceEntry, UUID> {
    List<FinanceEntry> findAllByGameIdOrderByCreatedAtAsc(UUID gameId);
    List<FinanceEntry> findAllByGameIdAndPlayerIdOrderByCreatedAtAsc(UUID gameId, UUID playerId);
}
