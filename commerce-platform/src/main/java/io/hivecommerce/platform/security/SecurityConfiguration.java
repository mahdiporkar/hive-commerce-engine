package io.hivecommerce.platform.security;

import io.hivecommerce.platform.identity.HiveIdentityFilter;
import io.hivecommerce.platform.identity.IdentityBindings;
import io.hivecommerce.platform.tenancy.DeploymentScope;
import io.hivecommerce.platform.web.ErrorResponses;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The engine is a stateless resource server behind the Hive BFF (ADR-0005). The BFF owns sessions and CSRF; the engine
 * validates the forwarded user token (issuer, audience, expiry, signature) and never issues or stores credentials.
 */
@Configuration
public class SecurityConfiguration {

  @Bean
  SecurityFilterChain commerceSecurity(HttpSecurity http, JwtDecoder decoder, IdentityBindings bindings, DeploymentScope deployment,
      ErrorResponses errors) throws Exception {
    AuthenticationEntryPoint entryPoint = (request, response, failure) -> {
      boolean invalidToken = failure instanceof InvalidBearerTokenException;
      errors.write(response, HttpStatus.UNAUTHORIZED, invalidToken ? "TOKEN_INVALID" : "AUTHENTICATION_REQUIRED",
          invalidToken ? "The bearer token was rejected" : "Sign in to continue");
    };
    http.csrf(csrf -> csrf.disable())
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .httpBasic(basic -> basic.disable())
        .formLogin(form -> form.disable())
        .logout(logout -> logout.disable())
        .requestCache(cache -> cache.disable())
        .authorizeHttpRequests(authorize -> authorize
            .requestMatchers("/actuator/health/**").permitAll()
            .requestMatchers("/actuator/**").denyAll()
            .requestMatchers("/v1/admin/**", "/v1/seller/**").authenticated()
            .anyRequest().permitAll())
        .oauth2ResourceServer(server -> server.jwt(jwt -> jwt.decoder(decoder)).authenticationEntryPoint(entryPoint))
        .exceptionHandling(handling -> handling.authenticationEntryPoint(entryPoint))
        .addFilterAfter(new HiveIdentityFilter(bindings, deployment, errors), BearerTokenAuthenticationFilter.class);
    return http.build();
  }

  @Bean
  JwtDecoder commerceJwtDecoder(@Value("${commerce.security.jwt.issuer}") String issuer,
      @Value("${commerce.security.jwt.jwk-set-uri}") String jwkSetUri,
      @Value("${commerce.security.jwt.audience}") String audience) {
    NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
    OAuth2TokenValidator<Jwt> audienceValidator = token -> token.getAudience() != null && token.getAudience().contains(audience)
        ? OAuth2TokenValidatorResult.success()
        : OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "audience not accepted", null));
    decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), audienceValidator));
    return decoder;
  }
}
