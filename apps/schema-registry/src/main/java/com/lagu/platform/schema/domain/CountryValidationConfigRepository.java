package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CountryValidationConfigRepository extends JpaRepository<CountryValidationConfig, UUID> {
    Optional<CountryValidationConfig> findByCountry(String country);
    Optional<CountryValidationConfig> findByCountryAndActiveTrue(String country);
    List<CountryValidationConfig> findByActiveTrueOrderByCountry();
}
