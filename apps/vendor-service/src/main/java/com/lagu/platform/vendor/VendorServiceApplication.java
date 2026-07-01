package com.lagu.platform.vendor;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.lagu.platform.vendor",
        "com.lagu.platform.common",
        "com.lagu.platform.security"
})
@EnableScheduling
public class VendorServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(VendorServiceApplication.class, args);
    }
}
