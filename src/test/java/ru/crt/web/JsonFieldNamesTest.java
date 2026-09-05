package ru.crt.web;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.crt.AbstractIntegrationTest;
import ru.crt.TestFixtures;
import tools.jackson.databind.ObjectMapper;

/** Имена полей в выдаче — внешний контракт, держащийся на {@code @JsonNaming}; без этого теста снятая аннотация переименовала бы поля молча. Маппер — из контекста. */
class JsonFieldNamesTest extends AbstractIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    private Set<String> keysOf(Object value) {
        return Set.copyOf(objectMapper.valueToTree(value).propertyNames());
    }

    @Test
    void searchRowKeepsCrtShFieldNames() {
        assertEquals(Set.of("id", "issuer_ca_id", "issuer_name", "common_name", "name_value",
                        "entry_timestamp", "not_before", "not_after", "serial_number", "result_count"),
                keysOf(TestFixtures.searchRow(1, "cn", "cn\nsan", "issuer")));
    }

    @Test
    void certDetailsKeepsCrtShFieldNames() {
        CertDetails.LogOccurrence occurrence =
                new CertDetails.LogOccurrence("https://log/", "d", "op", 5, "2026-01-01T00:00:00");
        CertDetails details = TestFixtures.certDetails(true, List.of(occurrence), "text", "issuer");

        assertEquals(Set.of("id", "issuer_ca_id", "issuer_name", "common_name", "name_value",
                        "domains", "log_url", "log_index", "sha256", "sha1", "serial_number",
                        "subject_dn", "issuer_dn", "not_before", "not_after", "entry_timestamp",
                        "is_precert", "occurrences", "text", "pem"),
                keysOf(details));
        assertEquals(Set.of("log_url", "description", "operator", "log_index", "entry_timestamp"),
                keysOf(occurrence));
    }

    @Test
    void logStatusesKeepFieldNamesReadByTheStatusWidget() {
        assertEquals(Set.of("url", "description", "operator", "last_synced_index", "tree_size",
                        "last_error", "updated_at"),
                keysOf(new LogStatus("https://log/", "d", "op", 1, 2, null, "2026-01-01T00:00:00")));
        assertEquals(Set.of("url", "description", "operator", "last_synced_index", "tree_size",
                        "ok", "updated_at"),
                keysOf(new LogPublicStatus("https://log/", "d", "op", 1, 2, true, "2026-01-01T00:00:00")));
    }
}
