package io.hivecommerce.ordering.infrastructure;

import io.hivecommerce.ordering.domain.ProductReview;
import io.hivecommerce.ordering.domain.ReviewRepository;
import io.hivecommerce.shared.DomainRuleException;
import io.hivecommerce.shared.TenantScope;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcReviewRepository implements ReviewRepository {
  private final JdbcClient jdbc;

  JdbcReviewRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(ProductReview r) {
    try {
      jdbc.sql("""
          insert into ordering.product_review(id, tenant_id, store_id, product_id, customer_id, rating, body, status, created_at)
          values (:id, :tenant, :store, :product, :customer, :rating, :body, :status, :created)""")
          .param("id", r.id()).param("tenant", r.scope().tenantId()).param("store", r.scope().storeId()).param("product", r.productId()).param("customer", r.customerId())
          .param("rating", r.rating()).param("body", r.body()).param("status", r.status().name()).param("created", Timestamp.from(r.createdAt())).update();
    } catch (DuplicateKeyException e) {
      throw new DomainRuleException("REVIEW_EXISTS", "You have already reviewed this product");
    }
  }

  @Override
  public List<ProductReview> findVisibleByProduct(TenantScope scope, UUID productId, int limit) {
    return jdbc.sql("select * from ordering.product_review where tenant_id = :tenant and store_id = :store and product_id = :product and status = 'VISIBLE' order by created_at desc limit :limit")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("product", productId).param("limit", limit).query((rs, n) -> read(rs, scope)).list();
  }

  @Override
  public List<ProductReview> findRecent(TenantScope scope, int limit) {
    return jdbc.sql("select * from ordering.product_review where tenant_id = :tenant and store_id = :store order by created_at desc limit :limit")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("limit", limit).query((rs, n) -> read(rs, scope)).list();
  }

  @Override
  public boolean hide(TenantScope scope, UUID reviewId) {
    return jdbc.sql("update ordering.product_review set status = 'HIDDEN' where id = :id and tenant_id = :tenant and store_id = :store")
        .param("id", reviewId).param("tenant", scope.tenantId()).param("store", scope.storeId()).update() == 1;
  }

  private static ProductReview read(ResultSet rs, TenantScope scope) throws SQLException {
    return new ProductReview(rs.getObject("id", UUID.class), scope, rs.getObject("product_id", UUID.class), rs.getObject("customer_id", UUID.class), rs.getInt("rating"),
        rs.getString("body"), ProductReview.Status.valueOf(rs.getString("status")), rs.getTimestamp("created_at").toInstant());
  }
}
