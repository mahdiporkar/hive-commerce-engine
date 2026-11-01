package io.hivecommerce.platform.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the Hive route operation key that must deliver requests to this handler. For operator handlers this is a
 * second line of defense behind the BFF: if an {@code /v1/admin/**} path were registered in Hive under a weaker
 * operation (for example HYBRID), the BFF would forward a different {@code X-Hive-Operation} and Commerce refuses.
 * The keys are also checked against {@code hive/templates/commerce-service.json} by a build test.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface HiveOperation {
  String value();
}
