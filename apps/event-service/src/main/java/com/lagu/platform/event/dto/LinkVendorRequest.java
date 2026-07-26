package com.lagu.platform.event.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class LinkVendorRequest {

    /** e.g. BIRTHDAY_EVENT_VENUE, EVENT_PHOTOGRAPHERS — see schema-registry RelationshipDefinition. */
    @NotBlank
    private String relationshipName;

    @NotNull
    private UUID targetRecordId;
}
