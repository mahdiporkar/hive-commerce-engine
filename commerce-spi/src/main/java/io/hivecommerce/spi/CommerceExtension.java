package io.hivecommerce.spi;

/**
 * Base contract of every extension, discovered with {@link java.util.ServiceLoader} from JARs in the client's engine image
 * (docs/architecture/extension-system.md). Extensions run in-process and are reviewed like engine code. They can add
 * restrictions or data; they can never relax a core invariant. An exception thrown by an extension rejects the command.
 */
public interface CommerceExtension {
  int SPI_MAJOR = 1;

  /** Stable lowercase key, for example {@code gemstone-verification}. */
  String key();

  /** The SPI major this extension was built against. The engine refuses to start on a mismatch. */
  default int spiMajor() { return SPI_MAJOR; }
}
