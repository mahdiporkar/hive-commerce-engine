package io.hivecommerce.platform.web;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hiveplatform.spring.CorrelationId;
import io.hiveplatform.spring.Redaction;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps domain failures to the PlatformError shape; everything else falls through to Hive's {@code PlatformErrors}. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CommerceErrors {

  @ExceptionHandler(DomainValidationException.class)
  ResponseEntity<Map<String, Object>> validation(DomainValidationException error) {
    var body = base("VALIDATION_FAILED", "The request breaks one or more rules");
    body.put("violations", error.violations().stream()
        .map(v -> Map.of("field", v.field(), "message", Redaction.safeMessage(v.message()))).toList());
    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
  }

  @ExceptionHandler(DomainRuleException.class)
  ResponseEntity<Map<String, Object>> rule(DomainRuleException error) {
    return ResponseEntity.status(HttpStatus.CONFLICT).body(base(error.code(), Redaction.safeMessage(error.getMessage())));
  }

  private static LinkedHashMap<String, Object> base(String code, String message) {
    var body = new LinkedHashMap<String, Object>();
    body.put("code", code);
    body.put("message", message);
    body.put("correlationId", CorrelationId.current());
    return body;
  }
}
