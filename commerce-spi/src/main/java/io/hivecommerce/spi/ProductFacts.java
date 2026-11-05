package io.hivecommerce.spi;

import java.util.List;
import java.util.Map;

/** Read-only facts about a product, as plain data. */
public record ProductFacts(String productType, String kind, Map<String, Object> attributes, List<Map<String, Object>> variantAttributes) {}
