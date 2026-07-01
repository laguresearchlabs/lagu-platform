package com.lagu.platform.schema;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class SchemaRegistryApplication {
    public static void main(String[] args) {
        SpringApplication.run(SchemaRegistryApplication.class, args);
    }
}
