package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.schema.domain.SearchDefinition;
import com.lagu.platform.schema.domain.SearchDefinitionRepository;
import com.lagu.platform.schema.dto.SearchDefinitionRequest;
import com.lagu.platform.schema.dto.SearchDefinitionResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SearchDefinitionService {

    private final SearchDefinitionRepository repository;

    public List<SearchDefinitionResponse> list() {
        return repository.findAll().stream().map(this::toResponse).toList();
    }

    public SearchDefinitionResponse getByListingType(String listingType) {
        return toResponse(findByListingType(listingType));
    }

    @Transactional
    public SearchDefinitionResponse upsert(SearchDefinitionRequest req) {
        SearchDefinition def = repository.findByListingType(req.listingType())
                .orElseGet(SearchDefinition::new);
        applyRequest(def, req);
        SearchDefinition saved = repository.save(def);
        log.info("Upserted SearchDefinition for listingType={}", saved.getListingType());
        return toResponse(saved);
    }

    @Transactional
    public void delete(String listingType) {
        SearchDefinition def = findByListingType(listingType);
        repository.delete(def);
    }

    private SearchDefinition findByListingType(String listingType) {
        return repository.findByListingType(listingType)
                .orElseThrow(() -> new ResourceNotFoundException("SearchDefinition", listingType));
    }

    private void applyRequest(SearchDefinition def, SearchDefinitionRequest req) {
        def.setListingType(req.listingType());
        def.setConsumerFacets(req.consumerFacets());
        def.setAdminFacets(req.adminFacets());
        def.setDefaultSortField(req.defaultSortField());
        def.setDefaultSortDir(req.defaultSortDir() != null ? req.defaultSortDir() : "ASC");
        def.setBoostField(req.boostField());
    }

    public SearchDefinitionResponse toResponse(SearchDefinition sd) {
        return new SearchDefinitionResponse(
                sd.getId(), sd.getListingType(), sd.getConsumerFacets(), sd.getAdminFacets(),
                sd.getDefaultSortField(), sd.getDefaultSortDir(), sd.getBoostField(),
                sd.isActive(), sd.getCreatedAt(), sd.getUpdatedAt()
        );
    }
}
