package io.hivecommerce.spi;

import java.util.Map;
import java.util.Optional;

/** The calling extension's own documents, stored by the engine per store. Extensions never touch core tables. */
public interface ExtensionData {
  Optional<Map<String, Object>> get(String subjectType, String subjectId);
}
