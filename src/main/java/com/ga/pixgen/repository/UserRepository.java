package com.ga.pixgen.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.ga.pixgen.model.User;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    /**
     * Conditionally deduct {@code cost} credits from the given user. The
     * {@code credits >= :cost} predicate makes the deduction atomic at
     * the database level: callers that observe a return value of
     * {@code 0} know the user did not have enough credits and must
     * mark their job {@code FAILED} with reason {@code INSUFFICIENT_CREDITS}.
     * Used by {@code JobWorker} inside the per-user lock so two parallel
     * jobs of the same user cannot drive a balance negative.
     */
    @Modifying
    @Query(value = """
            UPDATE users
               SET credits = credits - :cost,
                   updated_at = NOW()
             WHERE id = :userId
               AND credits >= :cost
            """, nativeQuery = true)
    int deductCreditsIfSufficient(@Param("userId") Long userId, @Param("cost") int cost);
}
