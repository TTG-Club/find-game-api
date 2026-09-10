package club.ttg.findgame.report;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Хранилище жалоб на объявления. */
public interface GameReportRepository extends JpaRepository<GameReport, UUID> {

    boolean existsByGameIdAndReporterId(UUID gameId, UUID reporterId);

    Page<GameReport> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
