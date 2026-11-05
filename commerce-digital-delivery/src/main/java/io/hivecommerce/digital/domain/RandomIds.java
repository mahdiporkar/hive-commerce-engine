package io.hivecommerce.digital.domain;

import java.security.SecureRandom;

/** Unguessable public identifiers (≈131 random bits), so asset and upload ids cannot be enumerated (master prompt §13). */
public final class RandomIds {
  private static final String ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final SecureRandom RANDOM = new SecureRandom();

  private RandomIds() {}

  public static String next(String prefix) {
    var id = new StringBuilder(prefix);
    for (int i = 0; i < 22; i++) id.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
    return id.toString();
  }
}
