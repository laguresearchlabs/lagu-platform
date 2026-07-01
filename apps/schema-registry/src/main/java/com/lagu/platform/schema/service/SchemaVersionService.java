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
        ListingTypeDefinition def = listingTypeRepo.findByNameWithSectionsAndOrgIdIsNull(listingType)
                .orElseThrow(() -> new ResourceNotFoundException("ListingTypeDefinition", listingType));

        ListingTypeSchemaDto schemaDto = listingTypeService.toSchemaDto(def);

        int newVersion = def.getCurrentVersion() + 1;
        def.setCurrentVersion(newVersion);
        listingTypeRepo.save(def);

        Map<String, Object> snapshot = objectMapper.convertValue(schemaDto, new TypeReference<>() {});

        SchemaVersion sv = new SchemaVersion();
        sv.setListingType(listingType);
        sv.setVersion(newVersion);
        sv.setSchemaSnapshot(snapshot);
        sv.setChangeClassification("SAFE");
        sv.setChangeSummary(req.changeSummary());
        sv.setPublishedBy(publishedBy);
        SchemaVersion saved = schemaVersionRepo.save(sv);

        eventPublisher.publishSchemaPublished(listingType, newVersion, "SAFE", publishedBy);

        log.info("Published schema for listingType={} version={}", listingType, newVersion);
        return toResponse(saved);
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
