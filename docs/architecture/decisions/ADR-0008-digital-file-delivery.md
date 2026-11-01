# ADR-0008 — Digital file upload and delivery (Gap 5)

Status: proposed · Date: 2026-11-01

## Decision
- `FileStorageProvider` port with an S3-compatible adapter (MinIO locally). One private bucket per client, with keys `t/{tenant}/s/{store}/assets/{randomId}/{version}`.
- **Upload.** `POST /v1/seller/assets/uploads` returns a pre-signed PUT bound to the key, content type and maximum size. The browser uploads directly to storage. `POST .../uploads/{id}/finalize` then verifies size, checksum and MIME type (by content sniffing), runs `MalwareScanner` when configured, and creates a `digital_content_version`.
- **Download.** `POST /v1/shop/library/{entitlementId}/downloads` checks ownership, entitlement status, limits and revocation, then returns JSON `{url, expiresAt}` with a signed GET and a short TTL. No 302, because the BFF does not relay `Location`. Each grant is logged in `download_access_log`.
- Prompt text (small) may be returned inline after the same checks.
- Public previews live under a separate prefix or bucket. Public APIs never return paid object keys.
- Browsers must be able to reach the storage endpoint (a public hostname for signed URLs) while the bucket policy stays private.

## Consequences
Large files never pass through the BFF. Delivered content can be copied, and no DRM claims are made.
