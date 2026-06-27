package com.lagu.platform.workflow.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.workflow.service.StateMachineEngine;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class TransitionEventConsumer {

    private final StateMachineEngine engine;

    @KafkaListener(topics = PlatformTopics.RECORD_EVENTS, groupId = "workflow-service")
    public void onRecordEvent(@Payload RecordEvent event,
                              @Header(KafkaHeaders.RECEIVED_KEY) String key,
                              Acknowledgment ack) {
        if (!"STATUS_TRANSITION_REQUESTED".equals(event.getEventType())) {
            ack.acknowledge();
            return;
        }

        log.info("Processing transition request for record {} trigger={}",
                event.getRecordId(), event.getTriggerName());
        engine.processTransitionRequest(event);  // exceptions propagate → DLT after 3 retries
        ack.acknowledge();
    }
}
