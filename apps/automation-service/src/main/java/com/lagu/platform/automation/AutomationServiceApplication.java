package com.lagu.platform.automation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {
        "com.lagu.platform.automation",
        "com.lagu.platform.common",
        "com.lagu.platform.security"
})
public class AutomationServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AutomationServiceApplication.class, args);
    }
}
