package io.hivecommerce.shared;

import java.security.SecureRandom;
import java.util.UUID;

/** Time-ordered random identifiers (UUID version 7): index-friendly, unguessable enough for row ids. */
public final class Ids {
  private static final SecureRandom RANDOM = new SecureRandom();

  private Ids() {}

  public static UUID newId() {
    long millis = System.currentTimeMillis();
    long high = (millis << 16) | 0x7000L | (RANDOM.nextInt() & 0x0FFFL);
    long low = (RANDOM.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
    return new UUID(high, low);
  }
}
