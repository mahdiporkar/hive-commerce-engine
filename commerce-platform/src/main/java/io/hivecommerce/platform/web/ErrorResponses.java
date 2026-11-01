package io.hivecommerce.platform.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.hiveplatform.spring.CorrelationId;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.LinkedHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;

/** Writes Hive's {@code PlatformError} shape ({code, message, correlationId}) from filters and interceptors. */
@Component
public class ErrorResponses {
  private final ObjectMapper mapper;

  public ErrorResponses(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  public void write(HttpServletResponse response, HttpStatus status, String code, String message) throws IOException {
    var body = new LinkedHashMap<String, Object>();
    body.put("code", code);
    body.put("message", message);
    body.put("correlationId", CorrelationId.current());
    response.setStatus(status.value());
    response.setContentType(MediaType.APPLICATION_JSON_VALUE);
    mapper.writeValue(response.getOutputStream(), body);
  }
}
