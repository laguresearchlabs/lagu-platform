package com.lagu.platform.listing;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.lagu.platform.listing",
        "com.lagu.platform.common",
        "com.lagu.platform.security"
})
public class ListingServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(ListingServiceApplication.class, args);
    }
}
