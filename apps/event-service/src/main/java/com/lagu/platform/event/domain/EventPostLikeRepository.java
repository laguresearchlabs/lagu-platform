package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface EventPostLikeRepository extends JpaRepository<EventPostLike, EventPostLike.Id> {
    boolean existsByPostRecordIdAndUserId(UUID postRecordId, UUID userId);
    void deleteByPostRecordIdAndUserId(UUID postRecordId, UUID userId);
    long countByPostRecordId(UUID postRecordId);
}
