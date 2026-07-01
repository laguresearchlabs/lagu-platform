package com.lagu.platform.schema.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.schema.domain.DocumentRequirement;
import com.lagu.platform.schema.domain.DocumentRequirementRepository;
import com.lagu.platform.schema.dto.DocumentRequirementRequest;
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
public class DocumentRequirementService {

    private final DocumentRequirementRepository repository;

    public List<DocumentRequirement> list(String listingType) {
        return (listingType != null)
                ? repository.findByListingTypeAndActiveTrueOrderByDisplayOrder(listingType)
                : repository.findByListingTypeIsNullAndActiveTrueOrderByDisplayOrder();
    }

    public DocumentRequirement getById(UUID id) {
        return findById(id);
    }

    @Transactional
    public DocumentRequirement create(DocumentRequirementRequest req) {
        DocumentRequirement doc = new DocumentRequirement();
        applyRequest(doc, req);
        DocumentRequirement saved = repository.save(doc);
        log.info("Created DocumentRequirement: {}", saved.getCode());
        return saved;
    }

    @Transactional
    public DocumentRequirement update(UUID id, DocumentRequirementRequest req) {
        DocumentRequirement doc = findById(id);
        applyRequest(doc, req);
        return repository.save(doc);
    }

    @Transactional
    public DocumentRequirement toggleActive(UUID id) {
        DocumentRequirement doc = findById(id);
        doc.setActive(!doc.isActive());
        return repository.save(doc);
    }

    @Transactional
    public void delete(UUID id) {
        DocumentRequirement doc = findById(id);
        repository.delete(doc);
    }

    private DocumentRequirement findById(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("DocumentRequirement", id.toString()));
    }

    private void applyRequest(DocumentRequirement doc, DocumentRequirementRequest req) {
        doc.setListingType(req.listingType());
        doc.setCode(req.code());
        doc.setLabel(req.label());
        doc.setDescription(req.description());
        doc.setRequired(req.required());
        doc.setRequiredForTiers(req.requiredForTiers());
        doc.setExpiryTracked(req.expiryTracked());
        doc.setAllowedMimeTypes(req.allowedMimeTypes());
        doc.setMaxSizeMb(req.maxSizeMb() > 0 ? req.maxSizeMb() : 5);
        doc.setDisplayOrder(req.displayOrder());
    }
}
