package io.hivecommerce.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;
import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test
  void irrHasNoMinorUnits() {
    assertThat(Money.of("1500000", "IRR").amount()).isEqualByComparingTo("1500000");
    assertThatThrownBy(() -> Money.of("10.5", "IRR")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void roundsHalfEvenToCurrencyScale() {
    assertThat(Money.rounded(new BigDecimal("2.345"), Currency.getInstance("USD")).amount()).isEqualByComparingTo("2.34");
    assertThat(Money.rounded(new BigDecimal("2.5"), Currency.getInstance("IRR")).amount()).isEqualByComparingTo("2");
  }

  @Test
  void neverAddsDifferentCurrencies() {
    assertThatThrownBy(() -> Money.of("1", "USD").plus(Money.of("1", "EUR"))).hasMessageContaining("currency mismatch");
    assertThat(Money.of("1.10", "USD").plus(Money.of("2.05", "USD"))).isEqualTo(Money.of("3.15", "USD"));
  }
}
