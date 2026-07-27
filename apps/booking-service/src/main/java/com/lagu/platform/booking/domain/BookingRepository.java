package com.lagu.platform.booking.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByConsumerUserIdOrderByCreatedAtDesc(UUID consumerUserId);

    List<Booking> findByConsumerUserIdAndEventIdOrderByCreatedAtDesc(UUID consumerUserId, UUID eventId);

    List<Booking> findByVendorIdOrderByCreatedAtDesc(UUID vendorId);
}
