package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.schema.domain.TierEligibilityRule;
import com.lagu.platform.schema.domain.TierEligibilityRuleRepository;
import com.lagu.platform.schema.dto.TierRuleRequest;
import com.lagu.platform.schema.dto.TierRuleResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TierRuleService {

    private final TierEligibilityRuleRepository repository;

    public List<TierRuleResponse> list(String listingType, String tier) {
        List<TierEligibilityRule> rules = (listingType != null && tier != null)
                ? repository.findByListingTypeAndTierAndActiveTrueOrderByDisplayOrder(listingType, tier)
                : (listingType != null)
                    ? repository.findByListingTypeAndActiveTrueOrderByDisplayOrder(listingType)
                    : repository.findAll();
        return rules.stream().map(this::toResponse).toList();
    }

    public TierRuleResponse getById(UUID id) {
        return toResponse(findById(id));
    }

    @Transactional
    public TierRuleResponse create(TierRuleRequest req) {
        TierEligibilityRule rule = new TierEligibilityRule();
        applyRequest(rule, req);
        TierEligibilityRule saved = repository.save(rule);
        log.info("Created TierEligibilityRule: {} for {}/{}", saved.getDisplayName(), saved.getListingType(), saved.getTier());
        return toResponse(saved);
    }

    @Transactional
    public TierRuleResponse toggleActive(UUID id) {
        TierEligibilityRule rule = findById(id);
        rule.setActive(!rule.isActive());
        return toResponse(repository.save(rule));
    }

    @Transactional
    public void delete(UUID id) {
        TierEligibilityRule rule = findById(id);
        repository.delete(rule);
    }

    private TierEligibilityRule findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("TierEligibilityRule", id.toString()));
    }

    private void applyRequest(TierEligibilityRule rule, TierRuleRequest req) {
        rule.setListingType(req.listingType());
        rule.setTier(req.tier());
        rule.setRuleType(req.ruleType());
        rule.setDocumentCode(req.documentCode());
        rule.setFieldPath(req.fieldPath());
        rule.setOperator(req.operator());
        rule.setValue(req.value());
        rule.setMinCount(req.minCount());
        rule.setForceOverridable(req.forceOverridable());
        rule.setDisplayName(req.displayName());
        rule.setDescription(req.description());
        rule.setDisplayOrder(req.displayOrder());
    }

    public TierRuleResponse toResponse(TierEligibilityRule r) {
        return new TierRuleResponse(
                r.getId(), r.getListingType(), r.getTier(), r.getRuleType(),
                r.getDocumentCode(), r.getFieldPath(), r.getOperator(), r.getValue(),
                r.getMinCount(), r.isForceOverridable(), r.getDisplayName(), r.getDescription(),
                r.getDisplayOrder(), r.isActive(), r.getCreatedAt(), r.getUpdatedAt()
        );
    }
}
