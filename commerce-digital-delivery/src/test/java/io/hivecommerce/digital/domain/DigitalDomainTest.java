package io.hivecommerce.digital.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.DomainValidationException;
import io.hivecommerce.shared.TenantScope;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DigitalDomainTest {
  private static final TenantScope SCOPE = new TenantScope("default", UUID.randomUUID());
  private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
  private static final String SHA = "a".repeat(64);

  private static DigitalAsset asset(Integer limit, boolean updates) {
    var asset = DigitalAsset.create(RandomIds.next("da_"), SCOPE, UUID.randomUUID(), UUID.randomUUID(), DigitalAsset.Kind.FILE, "Guide",
        new DigitalAsset.LicenseTerms(limit, updates, null), NOW);
    asset.addVersion("k1", SHA, 100, "application/pdf", "NOT_SCANNED", NOW);
    return asset;
  }

  @Test
  void entitlementEnforcesLimitRevocationAndVersionPolicy() {
    var pinned = asset(2, false);
    var e = Entitlement.grant(UUID.randomUUID(), SCOPE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), pinned.productId(), pinned, NOW);
    pinned.addVersion("k2", SHA, 120, "application/pdf", "NOT_SCANNED", NOW);
    assertThat(e.issueDownload(2, NOW)).isEqualTo(1); // no updates: keeps the purchased version
    assertThat(e.issueDownload(2, NOW)).isEqualTo(1);
    assertThatThrownBy(() -> e.issueDownload(2, NOW)).isInstanceOfSatisfying(DomainRuleException.class, x -> assertThat(x.code()).isEqualTo("DOWNLOAD_LIMIT_REACHED"));
    var updating = asset(null, true);
    var u = Entitlement.grant(UUID.randomUUID(), SCOPE, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), updating.productId(), updating, NOW);
    assertThat(u.issueDownload(3, NOW)).isEqualTo(3);
    assertThat(u.revoke("REFUNDED", NOW)).isTrue();
    assertThatThrownBy(() -> u.issueDownload(3, NOW)).isInstanceOfSatisfying(DomainRuleException.class, x -> assertThat(x.code()).isEqualTo("ENTITLEMENT_REVOKED"));
  }

  @Test
  void uploadFinalizationChecksEverything() {
    var mimes = Set.of("application/pdf");
    var expires = NOW.plusSeconds(900);
    var ok = UploadSession.open("up_1", SCOPE, "da_1", "key", 100, SHA, "application/pdf", 1000, mimes, expires);
    ok.finalizeWith(100, SHA, "application/pdf", MalwareScanner.Result.NOT_SCANNED, NOW);
    assertThat(ok.status()).isEqualTo(UploadSession.Status.FINALIZED);
    assertThatThrownBy(() -> ok.finalizeWith(100, SHA, "application/pdf", MalwareScanner.Result.CLEAN, NOW)).isInstanceOf(DomainRuleException.class);
    for (var c : new Object[][] {{99L, SHA, "application/pdf", MalwareScanner.Result.CLEAN, "UPLOAD_SIZE_MISMATCH"},
        {100L, "b".repeat(64), "application/pdf", MalwareScanner.Result.CLEAN, "UPLOAD_CHECKSUM_MISMATCH"},
        {100L, SHA, "application/zip", MalwareScanner.Result.CLEAN, "UPLOAD_TYPE_MISMATCH"},
        {100L, SHA, "application/pdf", MalwareScanner.Result.INFECTED, "UPLOAD_MALWARE_DETECTED"}}) {
      var u = UploadSession.open("up_x", SCOPE, "da_1", "key", 100, SHA, "application/pdf", 1000, mimes, expires);
      u.finalizeWith((Long) c[0], (String) c[1], (String) c[2], (MalwareScanner.Result) c[3], NOW);
      assertThat(u.rejection()).isEqualTo(c[4]);
    }
    assertThatThrownBy(() -> UploadSession.open("up_y", SCOPE, "da_1", "key", 5000, SHA, "application/x-msdownload", 1000, mimes, expires))
        .isInstanceOfSatisfying(DomainValidationException.class, x -> assertThat(x.violations()).hasSize(2));
  }

  @Test
  void sniffsRealContentTypes() {
    assertThat(MimeSniffer.detect("%PDF-1.7 ...".getBytes(StandardCharsets.US_ASCII))).isEqualTo("application/pdf");
    assertThat(MimeSniffer.detect(new byte[] {'P', 'K', 3, 4, 0})).isEqualTo("application/zip");
    assertThat(MimeSniffer.detect("یک پرامپت فارسی".getBytes(StandardCharsets.UTF_8))).isEqualTo("text/plain");
    assertThat(MimeSniffer.detect(new byte[] {'M', 'Z', 0, 0})).isEqualTo("application/octet-stream");
    assertThat(MimeSniffer.compatible("application/epub+zip", "application/zip")).isTrue();
    assertThat(MimeSniffer.compatible("application/pdf", "text/plain")).isFalse();
    assertThat(RandomIds.next("da_")).hasSize(25).startsWith("da_");
  }
}
