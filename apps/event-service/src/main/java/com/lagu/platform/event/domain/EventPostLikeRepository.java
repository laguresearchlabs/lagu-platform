package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface EventPostLikeRepository extends JpaRepository<EventPostLike, EventPostLike.Id> {
    boolean existsByPostRecordIdAndUserId(UUID postRecordId, UUID userId);
    void deleteByPostRecordIdAndUserId(UUID postRecordId, UUID userId);
    long countByPostRecordId(UUID postRecordId);

    /**
     * Like counts for a whole page of posts in one query. The per-post variants above cost two
     * statements each, so rendering a 20-post feed used to issue 40 — see
     * EventPostService.likesFor().
     */
    @Query("""
           select l.postRecordId, count(l)
           from EventPostLike l
           where l.postRecordId in :postIds
           group by l.postRecordId
           """)
    List<Object[]> countByPostRecordIdIn(@Param("postIds") Collection<UUID> postIds);

    /** Which of these posts the given user has liked — the batched form of existsBy. */
    @Query("select l.postRecordId from EventPostLike l where l.userId = :userId and l.postRecordId in :postIds")
    List<UUID> findLikedPostIds(@Param("userId") UUID userId, @Param("postIds") Collection<UUID> postIds);
}
