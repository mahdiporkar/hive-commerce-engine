package io.hivecommerce.platform.identity;

/** Request-bound {@link UserContext}. Controllers receive it as a method argument; nothing else should read this. */
public final class UserContextHolder {
  private static final ThreadLocal<UserContext> CURRENT = new ThreadLocal<>();

  private UserContextHolder() {}

  static void set(UserContext context) { CURRENT.set(context); }

  static void clear() { CURRENT.remove(); }

  public static UserContext current() {
    UserContext context = CURRENT.get();
    if (context == null) throw new IllegalStateException("No UserContext bound to this request");
    return context;
  }
}
