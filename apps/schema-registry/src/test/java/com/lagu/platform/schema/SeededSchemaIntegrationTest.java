package com.lagu.platform.schema;

import com.lagu.platform.common.visibility.VisibilityRules;
import com.lagu.platform.schema.domain.ListingTypeKind;
import com.lagu.platform.schema.dto.ListingTypeResponse;
import com.lagu.platform.schema.dto.ListingTypeSchemaDto;
import com.lagu.platform.schema.service.ListingTypeService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end over a real Postgres, with the seeder enabled: conditional-visibility rules and the
 * listing-type {@code kind} have to survive being written as jsonb/enum columns, read back through
 * JPA, and flattened into the schema DTO that events-ui consumes.
 *
 * <p>The sibling {@code SchemaRegistryApplicationTest} runs with the seeder off, so it only proves
 * migrations apply and the context starts — none of the data path below.
 */
@SpringBootTest
@Testcontainers
@ActiveProfiles("loc")
@TestPropertySource(properties = {
    "spring.kafka.bootstrap-servers=localhost:9092",
    "platform.seeder.enabled=true"
})
class SeededSchemaIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("platformdb")
            .withUsername("postgres")
            .withPassword("postgres");

    /** getSchema() is @Cacheable against a Redis CacheManager the app configures explicitly, so
     *  spring.cache.type=none does not disable it. Running a real Redis also exercises the
     *  versioned cache key that pinned-schema reads rely on. */
    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",             () -> postgres.getJdbcUrl() + "?TimeZone=UTC");
        r.add("spring.datasource.username",        postgres::getUsername);
        r.add("spring.datasource.password",        postgres::getPassword);
        r.add("spring.flyway.baseline-on-migrate", () -> "false");
        r.add("spring.data.redis.host",            redis::getHost);
        r.add("spring.data.redis.port",            () -> redis.getMappedPort(6379));
    }

    @Autowired ListingTypeService listingTypeService;

    private ListingTypeSchemaDto.FieldSchemaDto field(String listingType, String key) {
        return listingTypeService.getSchema(listingType).sections().stream()
                .flatMap(s -> s.fields().stream())
                .filter(f -> f.key().equals(key))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "field '" + key + "' not present in " + listingType + "'s schema"));
    }

    @Test
    void seededVisibilityRuleSurvivesTheRoundTripToTheSchemaDto() {
        var url = field("WEDDING_EVENT", "virtual_meeting_url");

        assertThat(url.visibleWhen()).isNotNull();
        // Shape, not just presence: this is the jsonb that both evaluators have to parse.
        assertThat(VisibilityRules.dependencies(url.visibleWhen())).containsExactly("is_virtual");
    }

    @Test
    void theSeededRuleEvaluatesAsIntended() {
        var rule = field("WEDDING_EVENT", "virtual_meeting_provider").visibleWhen();

        assertThat(VisibilityRules.isVisible(rule, Map.of("is_virtual", true))).isTrue();
        assertThat(VisibilityRules.isVisible(rule, Map.of("is_virtual", false))).isFalse();
        assertThat(VisibilityRules.isVisible(rule, Map.of())).isFalse();
    }

    @Test
    void theRuleAppliesInEveryTypeComposingTheSharedFieldGroup() {
        // event_visibility is shared by WEDDING_EVENT and BIRTHDAY_EVENT; a rule on a field group
        // entry travels with the group, which is the placement decision in ADR-19.
        assertThat(field("BIRTHDAY_EVENT", "virtual_meeting_url").visibleWhen()).isNotNull();
    }

    @Test
    void theControllingFieldIsPresentInEveryTypeThatCarriesTheRule() {
        // A rule referencing a field the type lacks would hide its section forever. This is the
        // invariant SchemaRuleValidator enforces at publish; assert the seed data satisfies it.
        for (String type : List.of("WEDDING_EVENT", "BIRTHDAY_EVENT")) {
            assertThat(field(type, "is_virtual")).isNotNull();
        }
    }

    @Test
    void unconditionalFieldsCarryNoRule() {
        assertThat(field("WEDDING_EVENT", "is_virtual").visibleWhen()).isNull();
    }

    @Test
    void eventTypesAreDiscoverableByKindRatherThanNameSuffix() {
        // What events-ui's listEventTypes() now filters on, replacing name.endsWith("_EVENT").
        List<ListingTypeResponse> types = listingTypeService.list();

        assertThat(types).filteredOn(t -> t.kind() == ListingTypeKind.EVENT)
                .extracting(ListingTypeResponse::name)
                .contains("WEDDING_EVENT", "BIRTHDAY_EVENT", "CORPORATE_EVENT");

        assertThat(types).filteredOn(t -> "VENUE".equals(t.name()))
                .allSatisfy(t -> assertThat(t.kind()).isEqualTo(ListingTypeKind.LISTING));
        assertThat(types).filteredOn(t -> "EVENT_POST".equals(t.name()))
                .allSatisfy(t -> assertThat(t.kind()).isEqualTo(ListingTypeKind.SOCIAL));
    }

    @Test
    void eventTypesCarryTheIconAndColourTheClientRendersThemWith() {
        // events-ui deleted its per-event-type theme table in favour of these, so a missing
        // colour here means an event silently renders with the neutral fallback.
        assertThat(listingTypeService.list())
                .filteredOn(t -> t.kind() == ListingTypeKind.EVENT)
                .allSatisfy(t -> {
                    assertThat(t.icon()).as("icon for %s", t.name()).isNotBlank();
                    assertThat(t.color()).as("colour for %s", t.name()).isNotBlank();
                });
    }
}
