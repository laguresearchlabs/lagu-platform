package com.lagu.platform.event.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventInvitationRepository extends JpaRepository<EventInvitation, UUID> {

    List<EventInvitation> findByEventIdAndStatusOrderByCreatedAtDesc(UUID eventId, String status);

    Optional<EventInvitation> findByIdAndEventId(UUID id, UUID eventId);

    /** The duplicate check behind the partial unique indexes — see V9. */
    Optional<EventInvitation> findByEventIdAndEmailAndStatus(UUID eventId, String email, String status);

    Optional<EventInvitation> findByEventIdAndPhoneAndStatus(UUID eventId, String phone, String status);

    /**
     * Everything waiting for a contact, across every event.
     *
     * <p>The claim path: someone signs in and asks what has been left for them. Deliberately not
     * scoped to one event — a guest invited to three parties before they had an account should
     * get all three on the visit they finally sign up, not one per link they happen to still have.
     */
    List<EventInvitation> findByEmailAndStatus(String email, String status);

    List<EventInvitation> findByPhoneAndStatus(String phone, String status);
}
