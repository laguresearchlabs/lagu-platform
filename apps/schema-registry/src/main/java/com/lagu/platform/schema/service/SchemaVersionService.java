package com.lagu.platform.schema.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.schema.domain.ListingTypeDefinition;
import com.lagu.platform.schema.domain.ListingTypeDefinitionRepository;
import com.lagu.platform.schema.domain.SchemaVersion;
import com.lagu.platform.schema.domain.SchemaVersionRepository;
import com.lagu.platform.schema.dto.ListingTypeSchemaDto;
import com.lagu.platform.schema.dto.PublishSchemaRequest;
import com.lagu.platform.schema.dto.SchemaVersionResponse;
import com.lagu.platform.schema.event.SchemaEventPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class SchemaVersionService {

    private final ListingTypeDefinitionRepository listingTypeRepo;
    private final SchemaVersionRepository schemaVersionRepo;
    private final ListingTypeService listingTypeService;
    private final SchemaEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @Transactional
    @CacheEvict(value = ListingTypeService.CACHE_SCHEMA, key = "#listingType")
    public SchemaVersionResponse publish(String listingType, PublishSchemaRequest req, String publishedBy) {
        ListingTypeDefinition def = listingTypeRepo.findByNameWithSectionsAndTenantIdIsNull(listingType)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", listingType));

        ListingTypeSchemaDto schemaDto = listingTypeService.toSchemaDto(def);

        // Cross-field rule checks can only run against a whole assembled type, so publish is the
        // gate: a rule referencing a field this type does not have would otherwise hide its
        // section forever, silently. See SchemaRuleValidator.
        SchemaRuleValidator.validate(schemaDto);

        Map<String, Object> previousSnapshot = schemaVersionRepo
                .findByListingTypeAndVersion(listingType, def.getCurrentVersion())
                .map(SchemaVersion::getSchemaSnapshot)
                .orElse(null);
        SchemaChangeClassifier.Result change = SchemaChangeClassifier.classify(previousSnapshot, schemaDto);

        int newVersion = def.getCurrentVersion() + 1;
        def.setCurrentVersion(newVersion);
        listingTypeRepo.save(def);

        Map<String, Object> snapshot = objectMapper.convertValue(schemaDto, new TypeReference<>() {});

        SchemaVersion sv = new SchemaVersion();
        sv.setListingType(listingType);
        sv.setVersion(newVersion);
        sv.setSchemaSnapshot(snapshot);
        sv.setChangeClassification(change.classification());
        sv.setChangeSummary(summaryOf(req, change));
        sv.setPublishedBy(publishedBy);
        SchemaVersion saved = schemaVersionRepo.save(sv);

        eventPublisher.publishSchemaPublished(listingType, newVersion, change.classification(), publishedBy);

        log.info("Published schema for listingType={} version={} classification={} reasons={}",
                listingType, newVersion, change.classification(), change.reasons());
        return toResponse(saved);
    }

    /**
     * The immutable schema snapshot taken when this version was published.
     *
     * <p>Cached under a versioned key in the same cache as the live schema. Publishing evicts only
     * the unversioned key, which is correct: a published snapshot never changes, so these entries
     * stay valid forever.
     */
    @Cacheable(value = ListingTypeService.CACHE_SCHEMA, key = "#listingType + ':v' + #version")
    public ListingTypeSchemaDto getSchemaAtVersion(String listingType, int version) {
        SchemaVersion sv = schemaVersionRepo.findByListingTypeAndVersion(listingType, version)
                .orElseThrow(() -> new ResourceNotFoundException("SchemaVersion",
                        listingType + ":" + version));

        Map<String, Object> snapshot = sv.getSchemaSnapshot();
        if (snapshot == null || snapshot.isEmpty()) {
            // A version row exists but carries no snapshot — treat as missing rather than
            // returning an empty schema, which would render as a form with no fields.
            throw new ResourceNotFoundException("SchemaVersion snapshot",
                    listingType + ":" + version);
        }
        return objectMapper.convertValue(snapshot, ListingTypeSchemaDto.class);
    }

    public SchemaVersionResponse getVersion(String listingType, int version) {
        SchemaVersion sv = schemaVersionRepo.findByListingTypeAndVersion(listingType, version)
                .orElseThrow(() -> new ResourceNotFoundException("SchemaVersion",
                        listingType + ":" + version));
        return toResponse(sv);
    }

    public List<SchemaVersionResponse> listVersions(String listingType) {
        return schemaVersionRepo
                .findByListingTypeOrderByVersionDesc(listingType, PageRequest.of(0, 10))
                .stream()
                .map(this::toResponse)
                .toList();
    }

    /** Keeps the author's own summary, appending why the publish was classified as it was so the
     *  version history explains itself without a diff. */
    private String summaryOf(PublishSchemaRequest req, SchemaChangeClassifier.Result change) {
        String authored = req.changeSummary();
        if (change.reasons().isEmpty()) return authored;
        String detail = String.join("; ", change.reasons());
        return authored == null || authored.isBlank() ? detail : authored + " — " + detail;
    }

    private SchemaVersionResponse toResponse(SchemaVersion sv) {
        return new SchemaVersionResponse(
                sv.getId(),
                sv.getListingType(),
                sv.getVersion(),
                sv.getChangeClassification(),
                sv.getChangeSummary(),
                sv.getPublishedBy(),
                sv.getPublishedAt()
        );
    }
}
