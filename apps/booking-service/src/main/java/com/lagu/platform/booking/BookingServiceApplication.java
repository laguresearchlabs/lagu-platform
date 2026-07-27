package com.lagu.platform.booking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.lagu.platform.booking",
        "com.lagu.platform.common",
        "com.lagu.platform.security"
})
@EnableScheduling  // OutboxRelay polling + cleanup
public class BookingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(BookingServiceApplication.class, args);
    }
}
