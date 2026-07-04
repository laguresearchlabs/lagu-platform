package com.lagu.platform.workflow;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling  // OutboxRelay polling + cleanup
@ComponentScan(basePackages = {
        "com.lagu.platform.workflow",
        "com.lagu.platform.common",
        "com.lagu.platform.security"
})
public class WorkflowServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(WorkflowServiceApplication.class, args);
    }
}
