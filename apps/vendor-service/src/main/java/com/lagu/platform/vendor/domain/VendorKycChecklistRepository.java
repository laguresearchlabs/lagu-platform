package com.lagu.platform.vendor.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface VendorKycChecklistRepository extends JpaRepository<VendorKycChecklist, UUID> {}
