package io.hivecommerce.platform.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a handler reached directly by an external provider through the ingress ({@code /hooks/**}), not through the Hive
 * BFF (ADR-0007). Such handlers authenticate the caller themselves (signatures) and must refuse sessions and identity
 * headers. They are the only handlers exempt from {@link HiveOperation}.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ProviderCallback {}
