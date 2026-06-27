package com.lagu.platform.document.event;

import com.lagu.platform.document.domain.Document;
import com.lagu.platform.events.DocumentEvent;
import com.lagu.platform.events.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class DocumentEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publish(Document doc, String eventType) {
        DocumentEvent event = DocumentEvent.builder()
                .eventType(eventType)
                .documentId(doc.getId())
                .userId(doc.getUserId())
                .orgId(doc.getOrgId())
                .documentType(doc.getDocumentType())
                .identitySubType(doc.getIdentitySubType())
                .status(doc.getStatus())
                .rejectionReason(doc.getRejectionReason())
                .fileName(doc.getFileName())
                .occurredAt(Instant.now())
                .build();

        String key = doc.getOrgId() + ":" + doc.getUserId();
        kafkaTemplate.send(PlatformTopics.DOCUMENT_EVENTS, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish DocumentEvent type={} doc={}", eventType, doc.getId(), ex);
                    }
                });
    }
}
