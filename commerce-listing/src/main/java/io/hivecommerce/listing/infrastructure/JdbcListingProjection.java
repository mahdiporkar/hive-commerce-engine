package io.hivecommerce.listing.infrastructure;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.hivecommerce.catalog.application.published.CatalogQueries.ProductSnapshot;
import io.hivecommerce.listing.application.ListingProjection;
import io.hivecommerce.shared.TenantScope;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL implementation of the published-listing search projection (typed facet rows, indexed). */
@Repository
class JdbcListingProjection implements ListingProjection {
  private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() {};
  private static final TypeReference<Map<String, String>> TEXT = new TypeReference<>() {};
  private static final TypeReference<List<Map<String, Object>>> LIST = new TypeReference<>() {};
  private final JdbcClient jdbc;
  private final ObjectMapper json;

  JdbcListingProjection(JdbcClient jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json.copy().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS);
  }

  @Override
  public void upsert(TenantScope scope, UUID listingId, ProductSnapshot p, List<Facet> facets) {
    var variants = p.variants().stream().map(v -> Map.<String, Object>of("variantId", v.variantId().toString(), "sku", v.sku(), "attributes", v.attributes())).toList();
    String searchText = (String.join(" ", p.title().values()) + " " + p.slug()).toLowerCase();
    jdbc.sql("""
        insert into listing.listing_view(listing_id, tenant_id, store_id, product_id, slug, title, category_id, kind, product_type_key, attributes, variants,
          search_text, published, published_at)
        values (:id, :tenant, :store, :product, :slug, cast(:title as jsonb), :category, :kind, :type, cast(:attributes as jsonb), cast(:variants as jsonb),
          :search, true, now())
        on conflict (listing_id) do update set slug = excluded.slug, title = excluded.title, category_id = excluded.category_id, kind = excluded.kind,
          product_type_key = excluded.product_type_key, attributes = excluded.attributes, variants = excluded.variants, search_text = excluded.search_text,
          published = true, published_at = now()""")
        .param("id", listingId).param("tenant", scope.tenantId()).param("store", scope.storeId()).param("product", p.productId()).param("slug", p.slug())
        .param("title", write(p.title())).param("category", p.categoryId()).param("kind", p.kind()).param("type", p.productTypeKey())
        .param("attributes", write(p.attributes())).param("variants", write(variants)).param("search", searchText).update();
    jdbc.sql("delete from listing.listing_facet where listing_id = :id and tenant_id = :tenant and store_id = :store")
        .param("id", listingId).param("tenant", scope.tenantId()).param("store", scope.storeId()).update();
    for (Facet f : facets) {
      jdbc.sql("insert into listing.listing_facet(listing_id, tenant_id, store_id, key, text_value, num_value) values (:id, :tenant, :store, :key, :text, :num)")
          .param("id", listingId).param("tenant", scope.tenantId()).param("store", scope.storeId()).param("key", f.key()).param("text", f.text())
          .param("num", f.number()).update();
    }
  }

  @Override
  public void markUnpublished(TenantScope scope, UUID listingId) {
    jdbc.sql("update listing.listing_view set published = false where listing_id = :id and tenant_id = :tenant and store_id = :store")
        .param("id", listingId).param("tenant", scope.tenantId()).param("store", scope.storeId()).update();
  }

  @Override
  public Page search(TenantScope scope, SearchQuery q) {
    var where = new StringBuilder("v.tenant_id = :tenant and v.store_id = :store and v.published");
    var params = new java.util.LinkedHashMap<String, Object>();
    params.put("tenant", scope.tenantId());
    params.put("store", scope.storeId());
    if (q.categoryIds() != null) {
      where.append(" and v.category_id in (:categories)");
      params.put("categories", q.categoryIds());
    }
    if (q.text() != null && !q.text().isBlank()) {
      where.append(" and v.search_text like :text escape '\\'");
      params.put("text", "%" + q.text().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
    }
    int n = 0;
    for (var e : q.equals().entrySet()) {
      where.append(" and exists (select 1 from listing.listing_facet f where f.listing_id = v.listing_id and f.key = :k").append(n)
          .append(" and f.text_value in (:v").append(n).append("))");
      params.put("k" + n, e.getKey());
      params.put("v" + n, e.getValue().isEmpty() ? List.of("") : e.getValue());
      n++;
    }
    for (var e : q.min().entrySet()) {
      where.append(" and exists (select 1 from listing.listing_facet f where f.listing_id = v.listing_id and f.key = :k").append(n)
          .append(" and f.num_value >= :v").append(n).append(")");
      params.put("k" + n, e.getKey());
      params.put("v" + n, e.getValue());
      n++;
    }
    for (var e : q.max().entrySet()) {
      where.append(" and exists (select 1 from listing.listing_facet f where f.listing_id = v.listing_id and f.key = :k").append(n)
          .append(" and f.num_value <= :v").append(n).append(")");
      params.put("k" + n, e.getKey());
      params.put("v" + n, e.getValue());
      n++;
    }
    String order = "title".equals(q.sort()) ? "v.title ->> 'en', v.slug" : "v.published_at desc, v.listing_id";
    long total = bind(jdbc.sql("select count(*) from listing.listing_view v where " + where), params).query(Long.class).single();
    var items = bind(jdbc.sql("select v.listing_id, v.product_id, v.slug, v.title, v.category_id, v.kind, v.attributes from listing.listing_view v where "
            + where + " order by " + order + " limit :limit offset :offset"), params)
        .param("limit", q.size()).param("offset", q.page() * q.size())
        .query((rs, i) -> new Card(rs.getObject("listing_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("slug"),
            read(rs.getString("title"), TEXT), rs.getObject("category_id", UUID.class), rs.getString("kind"), read(rs.getString("attributes"), MAP)))
        .list();
    var facets = bind(jdbc.sql("select f.key, f.text_value, count(distinct f.listing_id) from listing.listing_facet f join listing.listing_view v on v.listing_id = f.listing_id where "
            + where + " and f.text_value is not null group by f.key, f.text_value order by f.key, f.text_value"), params)
        .query((rs, i) -> new FacetCount(rs.getString(1), rs.getString(2), rs.getLong(3))).list();
    return new Page(items, total, q.page(), q.size(), facets);
  }

  @Override
  public Optional<Detail> bySlug(TenantScope scope, String slug) {
    return jdbc.sql("""
        select listing_id, product_id, slug, title, category_id, kind, attributes, variants from listing.listing_view
        where tenant_id = :tenant and store_id = :store and slug = :slug and published""")
        .param("tenant", scope.tenantId()).param("store", scope.storeId()).param("slug", slug)
        .query((rs, i) -> new Detail(rs.getObject("listing_id", UUID.class), rs.getObject("product_id", UUID.class), rs.getString("slug"),
            read(rs.getString("title"), TEXT), rs.getObject("category_id", UUID.class), rs.getString("kind"), read(rs.getString("attributes"), MAP),
            read(rs.getString("variants"), LIST)))
        .optional();
  }

  private static JdbcClient.StatementSpec bind(JdbcClient.StatementSpec statement, Map<String, Object> params) {
    for (var e : params.entrySet()) statement = statement.param(e.getKey(), e.getValue());
    return statement;
  }

  private String write(Object value) {
    try {
      return json.writeValueAsString(value);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private <T> T read(String value, TypeReference<T> type) {
    try {
      return json.readValue(value, type);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

}
