package io.hivecommerce.platform.web;

import io.hivecommerce.platform.identity.UserContext;
import io.hivecommerce.platform.identity.UserContextHolder;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.MethodParameter;
import org.springframework.core.Ordered;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfiguration implements WebMvcConfigurer {
  private final ErrorResponses errors;
  private final String routeKey;
  private final boolean enforceOperation;

  public WebConfiguration(ErrorResponses errors, @Value("${commerce.hive.route-key:commerce}") String routeKey,
      @Value("${commerce.hive.enforce-operation:true}") boolean enforceOperation) {
    this.errors = errors;
    this.routeKey = routeKey;
    this.enforceOperation = enforceOperation;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HiveOperationInterceptor(routeKey, enforceOperation, errors));
  }

  /** Controllers declare a {@link UserContext} parameter instead of reading the holder. */
  @Override
  public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
    resolvers.add(new HandlerMethodArgumentResolver() {
      @Override
      public boolean supportsParameter(MethodParameter parameter) { return parameter.getParameterType() == UserContext.class; }

      @Override
      public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mav, NativeWebRequest request, WebDataBinderFactory binders) {
        return UserContextHolder.current();
      }
    });
  }

  /** Right after Hive's correlation-id filter, so the log line carries the request's correlation id. */
  @Bean
  FilterRegistrationBean<AccessLogFilter> commerceAccessLog() {
    var registration = new FilterRegistrationBean<>(new AccessLogFilter());
    registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
    return registration;
  }
}
