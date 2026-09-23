# Grok image configuration synchronization

Synced heyi company image model grok-imagine-image-2.0 from local database to production, preserving local role as active non-default image connection. Existing gpt-image-2 default remains unchanged. The API credential was decrypted in memory and encrypted with the production model-secret key; source transport file removed after synchronization.

Production watermark provider changed from implicit DRY_RUN to AI_WATERMARK_V1. Detection model is grok-4.6. Backend image, mounts, unrelated environment (including Shopee and OSS) preserved. Backend startup completed; auth endpoint returned expected unauthenticated 401. Provider models endpoint returned HTTP 200 and listed grok-imagine-image-2.0. This validates configuration and authenticated discovery, not a full paid image edit.

Existing failed workflow preserves its original request and placeholder results. Start a new confirmed upload workflow to perform the real watermark image stage; do not relabel placeholder images as real outputs. No product was published by this configuration update.

Private rollback artifacts are stored at /opt/heyi/deployments/grok-image-live-20260921.
