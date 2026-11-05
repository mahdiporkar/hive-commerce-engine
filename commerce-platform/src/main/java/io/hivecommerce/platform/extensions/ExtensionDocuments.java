package io.hivecommerce.platform.extensions;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.shared.TenantScope;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Per-store JSON documents owned by extensions (table {@code platform.extension_data}, row-level security applies). */
@Component
public class ExtensionDocuments {
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  public ExtensionDocuments(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  public Optional<Map<String, Object>> find(TenantScope scope, String key, String subjectType, String subjectId) {
    return jdbc.sql("select document from platform.extension_data where tenant_id = :tenant and store_id = :store and extension_key = :key and subject_type = :type and subject_id = :id")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("key", key).param("type", subjectType).param("id", subjectId)
        .query(String.class).optional().map(this::read);
  }

  public void put(TenantScope scope, String key, String subjectType, String subjectId, Map<String, Object> document, UUID actor) {
    try {
      jdbc.sql("""
          insert into platform.extension_data(tenant_id, store_id, extension_key, subject_type, subject_id, document, updated_by)
          values (:tenant, :store, :key, :type, :id, cast(:document as jsonb), :actor)
          on conflict (tenant_id, store_id, extension_key, subject_type, subject_id)
          do update set document = excluded.document, updated_by = excluded.updated_by, updated_at = now()""")
          .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("key", key).param("type", subjectType).param("id", subjectId)
          .param("document", json.writeValueAsString(document)).param("actor", actor).update();
    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("document is not valid JSON data");
    }
  }

  private Map<String, Object> read(String value) {
    try {
      return json.readValue(value, new TypeReference<>() {});
    } catch (Exception e) {
      throw new IllegalStateException("Corrupt extension document", e);
    }
  }
}
