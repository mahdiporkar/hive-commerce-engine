package io.hivecommerce.spi;

import java.util.List;
import java.util.Map;

/** Accepts operator writes of this extension's documents (for example a certificate record). Without it, writes are refused. */
public interface ExtensionDataHandler extends CommerceExtension {
  List<Issue> validateData(String subjectType, String subjectId, Map<String, Object> document);
}
