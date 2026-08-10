package com.lagu.platform.document;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.lagu.platform.document",
        "com.lagu.platform.common",
        "com.lagu.platform.security",
        "com.lagu.platform.storage"
})
@EnableScheduling
public class DocumentServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(DocumentServiceApplication.class, args);
    }
}
