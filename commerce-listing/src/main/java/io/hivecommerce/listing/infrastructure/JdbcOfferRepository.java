package io.hivecommerce.listing.infrastructure;

import io.hivecommerce.listing.domain.OfferRepository;
import io.hivecommerce.listing.domain.SellerOffer;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.Money;
import io.hivecommerce.shared.TenantScope;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcOfferRepository implements OfferRepository {
  private final JdbcClient jdbc;

  JdbcOfferRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(SellerOffer o) {
    try {
      jdbc.sql("""
          insert into listing.seller_offer(id, tenant_id, store_id, seller_id, product_id, variant_id, price, currency, condition, unique_item, digital, seller_sku, status)
          values (:id, :tenant, :store, :seller, :product, :variant, :price, :currency, :condition, :unique, :digital, :sku, :status)""")
          .param("id", o.id()).param("tenant", o.scope().tenantId()).param("store", o.scope().storeId()).param("seller", o.sellerId())
          .param("product", o.productId()).param("variant", o.variantId()).param("price", o.price().amount()).param("currency", o.price().currency().getCurrencyCode())
          .param("condition", o.condition().name()).param("unique", o.uniqueItem()).param("digital", o.digital()).param("sku", o.sellerSku())
          .param("status", o.status().name()).update();
    } catch (DuplicateKeyException e) {
      String message = String.valueOf(e.getMostSpecificCause().getMessage());
      if (message.contains("seller_offer_seller_sku_uk")) throw new DomainRuleException("SELLER_SKU_TAKEN", "seller sku taken");
      throw new DomainRuleException("OFFER_EXISTS", "This seller already offers this variant");
    }
  }

  @Override
  public void update(SellerOffer o) {
    int updated = jdbc.sql("""
        update listing.seller_offer set price = :price, status = :status, revision = revision + 1, updated_at = now()
        where id = :id and tenant_id = :tenant and store_id = :store and revision = :revision""")
        .param("price", o.price().amount()).param("status", o.status().name()).param("id", o.id()).param("tenant", o.scope().tenantId())
        .param("store", o.scope().storeId()).param("revision", o.revision()).update();
    if (updated != 1) throw new OptimisticLockingFailureException("offer " + o.id() + " changed concurrently");
  }

  @Override
  public Optional<SellerOffer> findById(TenantScope scope, UUID id) {
    return select(scope, "id = :value", id).stream().findFirst();
  }

  @Override
  public List<SellerOffer> findActiveByProduct(TenantScope scope, UUID productId) {
    return select(scope, "product_id = :value and status = 'ACTIVE'", productId);
  }

  @Override
  public List<SellerOffer> findBySeller(TenantScope scope, UUID sellerId) {
    return select(scope, "seller_id = :value", sellerId);
  }

  @Override
  public java.util.Map<UUID, Money> lowestActivePrices(TenantScope scope, java.util.Collection<UUID> productIds) {
    if (productIds.isEmpty()) return java.util.Map.of();
    var out = new java.util.HashMap<UUID, Money>();
    jdbc.sql("select distinct on (product_id) product_id, price, currency from listing.seller_offer "
            + "where tenant_id = :tenant and store_id = :store and status = 'ACTIVE' and product_id in (:ids) order by product_id, price, id")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("ids", List.copyOf(productIds))
        .query((rs, n) -> out.put(rs.getObject("product_id", UUID.class), new Money(rs.getBigDecimal("price"), Currency.getInstance(rs.getString("currency")))))
        .list();
    return out;
  }

  private List<SellerOffer> select(TenantScope scope, String condition, UUID value) {
    return jdbc.sql("select id, seller_id, product_id, variant_id, price, currency, condition, unique_item, digital, seller_sku, status, revision "
            + "from listing.seller_offer where tenant_id = :tenant and store_id = :store and " + condition + " order by price, id")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("value", value)
        .query((rs, n) -> SellerOffer.restore(rs.getObject("id", UUID.class), scope, rs.getObject("seller_id", UUID.class), rs.getObject("product_id", UUID.class),
            rs.getObject("variant_id", UUID.class), new Money(rs.getBigDecimal("price"), Currency.getInstance(rs.getString("currency"))),
            SellerOffer.Condition.valueOf(rs.getString("condition")), rs.getBoolean("unique_item"), rs.getBoolean("digital"), rs.getString("seller_sku"),
            SellerOffer.Status.valueOf(rs.getString("status")), rs.getLong("revision")))
        .list();
  }
}
