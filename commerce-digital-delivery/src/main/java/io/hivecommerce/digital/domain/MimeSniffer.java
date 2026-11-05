package io.hivecommerce.digital.domain;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Detects the real content type from the first bytes of a file; the declared type must agree (ADR-0008). */
public final class MimeSniffer {
  private MimeSniffer() {}

  public static String detect(byte[] head) {
    if (startsWith(head, "%PDF-".getBytes(StandardCharsets.US_ASCII))) return "application/pdf";
    if (startsWith(head, new byte[] {'P', 'K', 3, 4})) return "application/zip";
    if (startsWith(head, new byte[] {(byte) 0x89, 'P', 'N', 'G'})) return "image/png";
    if (startsWith(head, new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) return "image/jpeg";
    if (startsWith(head, "ID3".getBytes(StandardCharsets.US_ASCII)) || startsWith(head, new byte[] {(byte) 0xFF, (byte) 0xFB})) return "audio/mpeg";
    if (head.length >= 12 && Arrays.equals(Arrays.copyOfRange(head, 4, 8), "ftyp".getBytes(StandardCharsets.US_ASCII))) return "video/mp4";
    if (isUtf8Text(head)) return "text/plain";
    return "application/octet-stream";
  }

  /** EPUB is a ZIP container; text/plain may carry a charset parameter. */
  public static boolean compatible(String declared, String sniffed) {
    String base = declared.split(";")[0].strip();
    if (base.equals(sniffed)) return true;
    return base.equals("application/epub+zip") && sniffed.equals("application/zip");
  }

  private static boolean startsWith(byte[] data, byte[] prefix) {
    return data.length >= prefix.length && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
  }

  private static boolean isUtf8Text(byte[] head) {
    if (head.length == 0) return false;
    for (byte b : head) if (b == 0) return false;
    try {
      StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(trimPartialCharacter(head)));
      return true;
    } catch (CharacterCodingException e) {
      return false;
    }
  }

  /** The sample may end in the middle of a multi-byte character. */
  private static byte[] trimPartialCharacter(byte[] head) {
    int end = head.length;
    for (int i = 1; i <= 3 && end - i >= 0; i++) {
      byte b = head[end - i];
      if ((b & 0xC0) == 0xC0) return Arrays.copyOf(head, end - i);
      if ((b & 0x80) == 0) break;
    }
    return head;
  }
}
