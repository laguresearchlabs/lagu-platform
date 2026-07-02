package com.lagu.platform.notification.service;

/** Thrown when an email fails to send, so the Kafka listener retries and eventually DLTs it. */
public class EmailDeliveryException extends RuntimeException {

    public EmailDeliveryException(String message, Throwable cause) {
        super(message, cause);
    }
}
