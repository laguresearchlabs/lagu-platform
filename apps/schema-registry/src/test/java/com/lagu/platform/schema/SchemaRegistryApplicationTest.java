//package com.lagu.platform.schema;
//
//import org.junit.jupiter.api.Test;
//import org.springframework.boot.test.context.SpringBootTest;
//import org.springframework.test.context.ActiveProfiles;
//import org.springframework.test.context.DynamicPropertyRegistry;
//import org.springframework.test.context.DynamicPropertySource;
//import org.springframework.test.context.TestPropertySource;
//import org.testcontainers.containers.PostgreSQLContainer;
//import org.testcontainers.junit.jupiter.Container;
//import org.testcontainers.junit.jupiter.Testcontainers;
//
//@SpringBootTest
//@Testcontainers
//@ActiveProfiles("loc")
//@TestPropertySource(properties = {
//    "spring.kafka.bootstrap-servers=localhost:9092",
//    "spring.data.redis.host=localhost",
//    "spring.data.redis.port=6380",
//    "platform.seeder.enabled=false"
//})
//class SchemaRegistryApplicationTest {
//
//    @Container
//    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
//            .withDatabaseName("platformdb")
//            .withUsername("postgres")
//            .withPassword("postgres");
//
//    @DynamicPropertySource
//    static void configure(DynamicPropertyRegistry r) {
//        r.add("spring.datasource.url",             () -> postgres.getJdbcUrl() + "?TimeZone=UTC");
//        r.add("spring.datasource.username",        postgres::getUsername);
//        r.add("spring.datasource.password",        postgres::getPassword);
//        r.add("spring.flyway.baseline-on-migrate", () -> "false");
//    }
//
//    @Test
//    void contextLoads() {
//        // verifies Spring context starts with Flyway migrations applied
//    }
//}
