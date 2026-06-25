package com.lagu.platform.metadata.config;

import com.lagu.platform.metadata.domain.AttributeDefinition;
import com.lagu.platform.metadata.domain.AttributeDefinitionRepository;
import com.lagu.platform.metadata.domain.AttributeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class MetadataSeeder implements ApplicationRunner {

    private final AttributeDefinitionRepository attributeRepository;

    @Value("${platform.seeder.enabled:true}")
    private boolean enabled;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        seedPlatformAttributes();
    }

    private void seedPlatformAttributes() {
        List<PlatformAttribute> defaults = List.of(
                new PlatformAttribute("name",          "Name",           AttributeType.TEXT,    true,  true),
                new PlatformAttribute("description",   "Description",    AttributeType.LONG_TEXT, false, false),
                new PlatformAttribute("phone",         "Phone",          AttributeType.PHONE,   false, false),
                new PlatformAttribute("email",         "Email",          AttributeType.EMAIL,   false, true),
                new PlatformAttribute("website",       "Website",        AttributeType.URL,     false, false),
                new PlatformAttribute("address_line1", "Address Line 1", AttributeType.TEXT,    false, false),
                new PlatformAttribute("address_line2", "Address Line 2", AttributeType.TEXT,    false, false),
                new PlatformAttribute("city",          "City",           AttributeType.TEXT,    false, true),
                new PlatformAttribute("state",         "State",          AttributeType.TEXT,    false, true),
                new PlatformAttribute("country",       "Country",        AttributeType.TEXT,    false, true),
                new PlatformAttribute("postal_code",   "Postal Code",    AttributeType.TEXT,    false, false),
                new PlatformAttribute("latitude",      "Latitude",       AttributeType.DECIMAL, false, false),
                new PlatformAttribute("longitude",     "Longitude",      AttributeType.DECIMAL, false, false),
                new PlatformAttribute("price",         "Price",          AttributeType.DECIMAL, false, true),
                new PlatformAttribute("capacity",      "Capacity",       AttributeType.NUMBER,  false, true),
                new PlatformAttribute("is_active",     "Active",         AttributeType.BOOLEAN, false, true)
        );

        int seeded = 0;
        for (PlatformAttribute pa : defaults) {
            if (attributeRepository.findByNameAndOrgIdIsNull(pa.name()).isEmpty()) {
                AttributeDefinition def = new AttributeDefinition();
                def.setName(pa.name());
                def.setLabel(pa.label());
                def.setAttributeType(pa.type());
                def.setRequired(pa.required());
                def.setSearchable(pa.searchable());
                def.setFilterable(pa.searchable());
                attributeRepository.save(def);
                seeded++;
            }
        }

        if (seeded > 0) {
            log.info("Seeded {} platform-level attributes", seeded);
        }
    }

    private record PlatformAttribute(String name, String label, AttributeType type,
                                     boolean required, boolean searchable) {}
}
