package com.lagu.platform.notification.event;

import com.lagu.platform.events.AutomationEvent;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.notification.service.NotificationDeliveryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutomationEventConsumer {

    private final NotificationDeliveryService deliveryService;

    @KafkaListener(topics = PlatformTopics.AUTOMATION_EVENTS, groupId = "notification-service")
    public void handle(AutomationEvent event, Acknowledgment ack) {
        try {
            if ("ACTION_SUCCEEDED".equals(event.getEventType())
                    && "SEND_NOTIFICATION".equals(event.getActionType())) {
                deliveryService.deliver(event);
            }
        } catch (Exception e) {
            log.error("Failed to deliver notification for trigger {}: {}",
                    event.getTriggerId(), e.getMessage(), e);
            throw e;  // propagate → DefaultErrorHandler retries → DLT
        }
        ack.acknowledge();
    }
}
