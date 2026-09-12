package club.ttg.findgame.report;

import club.ttg.findgame.report.api.GameReportResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.UUID;

/** Хранилище жалоб на объявления. */
public interface GameReportRepository extends JpaRepository<GameReport, UUID> {

    boolean existsByGameIdAndReporterId(UUID gameId, UUID reporterId);

    /**
     * Единая очередь модерации: все жалобы и скрытые игры, на которые никто
     * не жаловался. Скрытая игра с жалобами уже представлена их строками и
     * отдельным дублем в очередь не добавляется.
     */
    @Query(
            value = """
                    select new club.ttg.findgame.report.api.GameReportResponse(
                        coalesce(report.id, game.id),
                        game.id,
                        game.title,
                        case when game.deletedAt is not null then true else false end,
                        report.reporterId,
                        report.reason,
                        report.details,
                        coalesce(report.createdAt, game.deletedAt),
                        game.deletedAt,
                        game.deletionReason
                    )
                    from Game game
                    left join GameReport report on report.gameId = game.id
                    where report.id is not null or game.deletedAt is not null
                    order by coalesce(report.createdAt, game.deletedAt) desc
                    """,
            countQuery = """
                    select count(game.id)
                    from Game game
                    left join GameReport report on report.gameId = game.id
                    where report.id is not null or game.deletedAt is not null
                    """)
    Page<GameReportResponse> findModerationQueue(Pageable pageable);
}
