package club.ttg.findgame.chat;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatEventRepository extends JpaRepository<ChatEvent, UUID> {

    Optional<ChatEvent> findByAuthorIdAndClientMessageId(UUID authorId, UUID clientMessageId);

    /** Лента комнаты: страница истории от курсора назад. */
    List<ChatEvent> findByNexusIdAndCreatedAtLessThanOrderByCreatedAtDescIdDesc(
            UUID nexusId, Instant before, Pageable pageable);
}
