package io.hivecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalizedTextTest {
  @Test
  void acceptsPersianArabicAndEnglish() {
    var text = new LocalizedText(Map.of("fa", "یاقوت", "ar", "ياقوت", "en", " Ruby "));
    assertThat(text.values()).containsEntry("en", "Ruby").hasSize(3);
  }

  @Test
  void rejectsEmptyBlankAndBadLocales() {
    assertThatThrownBy(() -> new LocalizedText(Map.of())).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> LocalizedText.of("en", " ")).isInstanceOf(DomainValidationException.class);
    assertThatThrownBy(() -> LocalizedText.of("EN", "x")).isInstanceOf(DomainValidationException.class);
  }
}
