package club.ttg.findgame.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatEventRepository extends JpaRepository<ChatEvent, UUID> {

    Optional<ChatEvent> findByAuthorIdAndClientMessageId(UUID authorId, UUID clientMessageId);

    /**
     * Общий чат игры. Пустой `player_id` обязателен в условии: иначе в общую
     * ленту попала бы личная переписка мастера с игроками.
     */
    List<ChatEvent> findByGameIdAndSessionIdIsNullAndPlayerIdIsNullAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
            UUID gameId, Instant before, Pageable pageable);

    List<ChatEvent> findByGameIdAndSessionIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
            UUID gameId, UUID sessionId, Instant before, Pageable pageable);

    /** Личная переписка мастера с одним игроком. */
    List<ChatEvent> findByGameIdAndPlayerIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
            UUID gameId, UUID playerId, Instant before, Pageable pageable);
}
