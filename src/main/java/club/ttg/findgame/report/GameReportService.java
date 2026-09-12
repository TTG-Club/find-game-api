package club.ttg.findgame.report;

import club.ttg.findgame.game.Game;
import club.ttg.findgame.game.GameNotFoundException;
import club.ttg.findgame.game.GameRepository;
import club.ttg.findgame.report.api.CreateGameReportRequest;
import club.ttg.findgame.report.api.GameReportResponse;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Приём жалоб и очередь для модераторов. */
@Service
public class GameReportService {

    private final GameRepository gameRepository;
    private final GameReportRepository reportRepository;

    public GameReportService(GameRepository gameRepository, GameReportRepository reportRepository) {
        this.gameRepository = gameRepository;
        this.reportRepository = reportRepository;
    }

    /** Создаёт одну жалобу пользователя на игру. */
    @Transactional
    public void create(UUID reporterId, UUID gameId, CreateGameReportRequest request) {
        Game game = gameRepository.findByIdAndDeletedAtIsNull(gameId)
                .orElseThrow(() -> new GameNotFoundException(gameId));

        if (game.getMasterId().equals(reporterId)) {
            throw new GameReportNotAllowedException("Нельзя пожаловаться на свою игру");
        }
        if (reportRepository.existsByGameIdAndReporterId(gameId, reporterId)) {
            throw new GameReportAlreadyExistsException();
        }

        reportRepository.save(new GameReport(
                gameId,
                reporterId,
                request.reason(),
                normalizeDetails(request.details())));
    }

    /** Возвращает свежие жалобы и скрытые модераторами объявления. */
    @Transactional(readOnly = true)
    public Page<GameReportResponse> findAll(int page, int size) {
        return reportRepository.findModerationQueue(PageRequest.of(page, size));
    }

    private static String normalizeDetails(String details) {
        if (details == null) {
            return null;
        }
        String normalized = details.strip();

        return normalized.isEmpty() ? null : normalized;
    }
}
