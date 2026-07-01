package com.lagu.platform.metadata.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CountryValidationConfigRepository extends JpaRepository<CountryValidationConfig, UUID> {

    Optional<CountryValidationConfig> findByCountryAndActiveTrue(String country);
}
