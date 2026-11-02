package io.hivecommerce.listing.infrastructure;

import io.hivecommerce.listing.domain.ListingRepository;
import io.hivecommerce.shared.DomainEvent;
import io.hivecommerce.shared.TenantScope;
import java.util.List;
import org.springframework.stereotype.Component;

/** Appends offer events to the listing context's outbox (same outbox as listings). */
@Component
public class ListingEventsSink {
  private final ListingRepository listings;

  ListingEventsSink(ListingRepository listings) {
    this.listings = listings;
  }

  public void append(TenantScope scope, List<DomainEvent> events) {
    listings.appendEvents(scope, events);
  }
}
