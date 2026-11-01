# Extension system (SPI)

Client repositories add industry behavior without changing the core. They conform to the published `commerce-spi` artifact; the core never imports client code.

## Mechanism

- `commerce-spi` (Maven artifact, SemVer, independent of the engine version but released with it) contains interfaces and immutable DTOs only. It has no dependency on Spring, the domain classes or Hive.
- Client extension JARs are added to the client's engine image (ADR-0012) and discovered with `java.util.ServiceLoader`. Each extension declares `key`, `spiVersion` and `capabilities`. Startup fails if an extension requires an SPI major the engine does not provide.
- Extensions run in-process, so they are trusted code and reviewed like engine code. They get a restricted context: no repository access, only the facades and DTOs passed in.
- Extension state is stored through `ExtensionDataStore` (a JSONB document per `(extensionKey, subjectType, subjectId)` in the `platform` schema) or in the client's own tables (managed by the client's own migrations in a separate schema). Extensions never touch core tables.

## Extension points (v1)

| SPI | Called by | Purpose | Example |
|---|---|---|---|
| `ProductValidationExtension` | Catalog, on create/update | Extra validation beyond the schema | gemstone: certificate number format per laboratory |
| `ListingApprovalExtension` | Listing, on submit/approve | Adds checks or evidence to approval | gemstone: expert inspection required for > X carat; prompt: verification report |
| `SellerVerificationExtension` | Seller, on verify | Extra seller verification steps | — |
| `ProductPresentationExtension` | Listing public API | Extra public, non-secret data for a listing | gemstone: verification badge metadata; prompt: verification score (time-limited) |
| `FulfillmentStrategy` | Fulfillment | How a line kind is fulfilled | built-in: `PHYSICAL_SHIPMENT`, `DIGITAL_ENTITLEMENT`; future: services, bookings |
| `PricingModelStrategy` | Pricing | How a price is formed | built-in: `FIXED`; future: subscriptions, auctions |

Future product kinds (§11: services, subscriptions, bookings, bundles, rentals, auctions) get **strategy slots only**. They are not implemented.

## Rules

- An extension can **add** restrictions or data. It can never relax a core invariant (for example, it cannot allow quantity > 1 on a unique item, or skip payment verification).
- Extensions fail closed: an exception in a validation extension rejects the command with `EXTENSION_REJECTED`, and the failure is logged.
- `PromptVerification` and the gemstone verification extension live in repositories C and B. The marketplace works with no extensions installed (tested).
- Scenario D (handicraft) uses **zero** extensions: configuration only.
