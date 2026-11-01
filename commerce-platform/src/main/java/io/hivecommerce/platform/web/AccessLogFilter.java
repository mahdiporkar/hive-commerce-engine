package io.hivecommerce.platform.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * One line per request: method, path, status, duration and correlation id (from the MDC). Query strings are never
 * logged because they may carry cart tokens (ADR-0013); headers and bodies are never logged.
 */
public class AccessLogFilter extends OncePerRequestFilter {
  private static final Logger log = LoggerFactory.getLogger("commerce.access");

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    long started = System.nanoTime();
    try {
      chain.doFilter(request, response);
    } finally {
      if (!request.getRequestURI().startsWith("/actuator/")) {
        log.info("method={} path={} status={} durationMs={}", request.getMethod(), request.getRequestURI(), response.getStatus(),
            (System.nanoTime() - started) / 1_000_000);
      }
    }
  }
}
