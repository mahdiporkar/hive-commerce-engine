package io.hivecommerce.catalog.domain;

import io.hivecommerce.shared.LocalizedText;

/** One allowed value of an option-type attribute: a stable code plus localized labels. */
public record AttributeOption(String code, LocalizedText labels) {}
