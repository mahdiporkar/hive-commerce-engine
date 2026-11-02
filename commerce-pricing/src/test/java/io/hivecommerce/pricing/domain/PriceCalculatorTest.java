package io.hivecommerce.pricing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PriceCalculatorTest {
  private final UUID sellerA = UUID.randomUUID(), sellerB = UUID.randomUUID();

  @Test
  void quotesLinesTaxAndShippingPerSellerOrder() {
    var policy = new PriceCalculator.Policy(new BigDecimal("9"), new BigDecimal("500000"));
    var quote = PriceCalculator.quote(List.of(
        new PriceCalculator.Line("a", sellerA, true, Money.of("1250000", "IRR"), 2),
        new PriceCalculator.Line("b", sellerA, true, Money.of("333333", "IRR"), 1),
        new PriceCalculator.Line("c", sellerB, false, Money.of("99999", "IRR"), 1)), policy);
    assertThat(quote.subtotal()).isEqualTo(Money.of("2933332", "IRR"));
    assertThat(quote.tax()).isEqualTo(Money.of("225000", "IRR").plus(Money.of("30000", "IRR")).plus(Money.of("9000", "IRR")));
    assertThat(quote.shippingBySeller().get(sellerA)).isEqualTo(Money.of("500000", "IRR"));
    assertThat(quote.shippingBySeller().get(sellerB)).isEqualTo(Money.of("0", "IRR"));
    assertThat(quote.total()).isEqualTo(quote.subtotal().plus(quote.tax()).plus(quote.shipping()));
  }

  @Test
  void refusesMixedCurrenciesAndEmptyQuotes() {
    var policy = new PriceCalculator.Policy(BigDecimal.ZERO, BigDecimal.ZERO);
    assertThatThrownBy(() -> PriceCalculator.quote(List.of(
        new PriceCalculator.Line("a", sellerA, true, Money.of("10.00", "USD"), 1),
        new PriceCalculator.Line("b", sellerA, true, Money.of("10.00", "EUR"), 1)), policy)).isInstanceOf(DomainRuleException.class);
    assertThatThrownBy(() -> PriceCalculator.quote(List.of(), policy)).isInstanceOf(DomainRuleException.class);
  }

  @Test
  void roundsPerCurrency() {
    var quote = PriceCalculator.quote(List.of(new PriceCalculator.Line("a", sellerA, false, Money.of("9.99", "USD"), 3)),
        new PriceCalculator.Policy(new BigDecimal("7.5"), BigDecimal.ZERO));
    assertThat(quote.tax()).isEqualTo(Money.of("2.25", "USD"));
  }
}
