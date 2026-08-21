package club.ttg.findgame.profile;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.UUID;

public interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO user_profiles (user_id, created_at, updated_at)
            VALUES (:userId, :now, :now)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    void insertUserProfileIfMissing(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying
    @Query(value = """
            INSERT INTO master_profiles (user_id)
            VALUES (:userId)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    void insertMasterProfileIfMissing(@Param("userId") UUID userId);

    @Modifying
    @Query(value = """
            INSERT INTO player_profiles (user_id)
            VALUES (:userId)
            ON CONFLICT (user_id) DO NOTHING
            """, nativeQuery = true)
    void insertPlayerProfileIfMissing(@Param("userId") UUID userId);
}
