package io.hivecommerce.platform.web;

import io.hivecommerce.platform.identity.HiveHeaders;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** Enforces {@link HiveOperation}: operator handlers answer only to their declared Hive route and operation. */
public class HiveOperationInterceptor implements HandlerInterceptor {
  public static final String ADMIN_PREFIX = "/v1/admin/";
  private final String routeKey;
  private final boolean enforce;
  private final ErrorResponses errors;

  public HiveOperationInterceptor(String routeKey, boolean enforce, ErrorResponses errors) {
    this.routeKey = routeKey;
    this.enforce = enforce;
    this.errors = errors;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
    if (!(handler instanceof HandlerMethod method)) return true;
    HiveOperation declared = method.getMethodAnnotation(HiveOperation.class);
    if (declared == null) {
      if (!request.getRequestURI().startsWith(ADMIN_PREFIX)) return true;
      // Fail closed: an operator endpoint without a declared Hive operation is a programming error.
      errors.write(response, HttpStatus.FORBIDDEN, "OPERATION_UNDECLARED", "This operation is not available");
      return false;
    }
    if (!enforce) return true;
    if (!routeKey.equals(request.getHeader(HiveHeaders.ROUTE)) || !declared.value().equals(request.getHeader(HiveHeaders.OPERATION))) {
      errors.write(response, HttpStatus.FORBIDDEN, "ROUTE_OPERATION_MISMATCH", "This operation must be called through its registered route");
      return false;
    }
    return true;
  }
}
