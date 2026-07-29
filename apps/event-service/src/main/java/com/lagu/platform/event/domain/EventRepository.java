package com.lagu.platform.event.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface EventRepository extends JpaRepository<Event, UUID> {
    List<Event> findByOwnerUserId(UUID ownerUserId);

    /** Platform-admin listing across every event, regardless of membership — see
     *  EventService.listForAdmin(). Filters are optional (null = no constraint). */
    @Query("SELECT e FROM Event e " +
           "WHERE (:objectType IS NULL OR e.objectType = :objectType) " +
           "AND (:status IS NULL OR e.status = :status)")
    Page<Event> search(@Param("objectType") String objectType,
                        @Param("status") String status,
                        Pageable pageable);
}
