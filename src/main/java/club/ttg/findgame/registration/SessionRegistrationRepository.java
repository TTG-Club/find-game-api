package club.ttg.findgame.registration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRegistrationRepository extends JpaRepository<SessionRegistration, UUID> {

    boolean existsBySessionIdAndPlayerId(UUID sessionId, UUID playerId);

    Optional<SessionRegistration> findBySessionIdAndPlayerId(UUID sessionId, UUID playerId);

    List<SessionRegistration> findAllBySessionIdOrderByCreatedAtAsc(UUID sessionId);

    List<SessionRegistration> findAllBySessionIdIn(Collection<UUID> sessionIds);

    long countBySessionId(UUID sessionId);

    /**
     * Убирает игрока из перечисленных сессий. Мастер исключает его из игры
     * целиком, и участие снимается во всех незакрытых встречах; в закрытых
     * оно остаётся историей.
     */
    void deleteBySessionIdInAndPlayerId(Collection<UUID> sessionIds, UUID playerId);
}
