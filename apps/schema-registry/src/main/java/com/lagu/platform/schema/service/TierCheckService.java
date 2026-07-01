package com.lagu.platform.schema.service;

import com.lagu.platform.schema.domain.TierEligibilityRule;
import com.lagu.platform.schema.domain.TierEligibilityRuleRepository;
import com.lagu.platform.schema.dto.TierCheckResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class TierCheckService {

    private final TierEligibilityRuleRepository ruleRepo;

    public TierCheckResponse check(String recordId, String targetTier, String listingType) {
        List<TierEligibilityRule> rules =
                ruleRepo.findByListingTypeAndTierAndActiveTrueOrderByDisplayOrder(listingType, targetTier);

        List<TierCheckResponse.RuleCheckItem> checks = rules.stream()
                .map(rule -> switch (rule.getRuleType()) {
                    case "DOCUMENT_VERIFIED" -> new TierCheckResponse.RuleCheckItem(
                            rule.getDisplayName(),
                            false,
                            "Document " + rule.getDocumentCode() + " not yet verified"
                    );
                    case "FIELD_CONDITION" -> new TierCheckResponse.RuleCheckItem(
                            rule.getDisplayName(),
                            false,
                            "Field condition check pending integration"
                    );
                    case "MIN_BOOKINGS" -> new TierCheckResponse.RuleCheckItem(
                            rule.getDisplayName(),
                            false,
                            "Minimum bookings check pending integration"
                    );
                    default -> new TierCheckResponse.RuleCheckItem(
                            rule.getDisplayName(),
                            false,
                            "Unknown rule type: " + rule.getRuleType()
                    );
                })
                .toList();

        boolean eligible = checks.stream().allMatch(TierCheckResponse.RuleCheckItem::satisfied);

        log.debug("Tier check: recordId={} listingType={} targetTier={} eligible={} rules={}",
                recordId, listingType, targetTier, eligible, rules.size());

        return new TierCheckResponse(listingType, targetTier, eligible, checks);
    }
}
