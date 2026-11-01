package io.hivecommerce.app.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/**
 * A minimal OIDC stand-in for engine-level tests: serves a JWKS over HTTP and mints RS256 tokens. Through-Hive tests
 * use a real Keycloak instead (tests/e2e).
 */
public final class TestIdentityProvider implements AutoCloseable {
  public static final String ISSUER = "https://idp.test/realms/commerce";
  public static final String AUDIENCE = "commerce-engine";
  private final RSAKey key;
  private final RSAKey foreignKey;
  private final HttpServer server;

  public TestIdentityProvider() {
    try {
      key = new RSAKeyGenerator(2048).keyID("test-key").generate();
      foreignKey = new RSAKeyGenerator(2048).keyID("test-key").generate();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] jwks = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      server.createContext("/jwks", exchange -> {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, jwks.length);
        exchange.getResponseBody().write(jwks);
        exchange.close();
      });
      server.start();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public String jwksUri() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks"; }

  public String token(String subject) { return token(subject, ISSUER, AUDIENCE, Instant.now().plusSeconds(300), false); }

  public String token(String subject, String issuer, String audience, Instant expiresAt, boolean signWithForeignKey) {
    try {
      var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(List.of(audience, "account"))
          .issueTime(Date.from(Instant.now().minusSeconds(5))).expirationTime(Date.from(expiresAt)).build();
      var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").type(JOSEObjectType.JWT).build(), claims);
      jwt.sign(new RSASSASigner(signWithForeignKey ? foreignKey : key));
      return jwt.serialize();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Override
  public void close() throws IOException {
    server.stop(0);
  }
}
