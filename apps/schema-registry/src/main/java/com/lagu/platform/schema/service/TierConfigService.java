package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.schema.domain.TierConfiguration;
import com.lagu.platform.schema.domain.TierConfigurationRepository;
import com.lagu.platform.schema.dto.TierConfigRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TierConfigService {

    public static final String CACHE_TIER_CONFIG = "schema-registry:tier-config";

    private final TierConfigurationRepository repository;

    public List<TierConfiguration> list() {
        return repository.findByActiveTrueOrderByTierName();
    }

    @Cacheable(value = CACHE_TIER_CONFIG, key = "#tierName + ':' + (#listingType ?: 'null')")
    public TierConfiguration getByTierName(String tierName, String listingType) {
        return (listingType != null)
                ? repository.findByTierNameAndListingType(tierName, listingType)
                        .orElseGet(() -> repository.findByTierNameAndListingTypeIsNull(tierName)
                                .orElseThrow(() -> new ResourceNotFoundException("TierConfiguration", tierName)))
                : repository.findByTierNameAndListingTypeIsNull(tierName)
                        .orElseThrow(() -> new ResourceNotFoundException("TierConfiguration", tierName));
    }

    @Transactional
    public TierConfiguration create(TierConfigRequest req) {
        TierConfiguration tc = new TierConfiguration();
        applyRequest(tc, req);
        TierConfiguration saved = repository.save(tc);
        log.info("Created TierConfiguration: {}/{}", saved.getTierName(), saved.getListingType());
        return saved;
    }

    @Transactional
    public TierConfiguration update(UUID id, TierConfigRequest req) {
        TierConfiguration tc = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TierConfiguration", id.toString()));
        applyRequest(tc, req);
        return repository.save(tc);
    }

    private void applyRequest(TierConfiguration tc, TierConfigRequest req) {
        tc.setTierName(req.tierName());
        tc.setListingType(req.listingType());
        if (req.commissionRate() != null) tc.setCommissionRate(req.commissionRate());
        tc.setMaxActiveBookings(req.maxActiveBookings());
        if (req.searchBoostFactor() != null) tc.setSearchBoostFactor(req.searchBoostFactor());
        if (req.responseSlaHours() > 0) tc.setResponseSlaHours(req.responseSlaHours());
        tc.setExpiryDays(req.expiryDays());
        tc.setFeatures(req.features());
    }
}
