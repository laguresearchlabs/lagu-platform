package com.lagu.platform.schema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(scanBasePackages = {
        "com.lagu.platform.schema",
        "com.lagu.platform.common",
        "com.lagu.platform.security"
})
@EnableCaching
@EnableScheduling  // OutboxRelay polling + cleanup
public class SchemaRegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchemaRegistryApplication.class, args);
    }
}
