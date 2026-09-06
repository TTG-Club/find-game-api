package club.ttg.findgame.follow;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface UserFollowRepository extends JpaRepository<UserFollow, UUID> {

    List<UserFollow> findAllByOwnerIdAndKindOrderByCreatedAtDesc(UUID ownerId, FollowKind kind);

    boolean existsByOwnerIdAndTargetIdAndKind(UUID ownerId, UUID targetId, FollowKind kind);

    /** Кому рассылать новую игру мастера. */
    List<UserFollow> findAllByTargetIdAndKind(UUID targetId, FollowKind kind);

    void deleteByOwnerIdAndTargetIdAndKind(UUID ownerId, UUID targetId, FollowKind kind);
}
