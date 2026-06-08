# Detail Attachment Media Appearance

Status: planning and design grilling.

## Scope

This feature covers how image and video attachments are presented inside
`DetailActivity`'s attachment list. It is separate from Thing Card Appearance
unless a later decision explicitly reuses shared model pieces.

## Related Context

- `CONTEXT.md` defines Thing Card Appearance, Thing Card Media Target Aspect
  Ratio, and Thing Card Media Crop for card surfaces.
- ADR: `docs/adr/0004-detail-attachment-media-appearance.md`.
- `docs/features/thing-card-appearance/` and
  `docs/features/thing-card-media-target-geometry/` describe the existing card
  media customization model that this feature may borrow from.
- Current `DetailActivity` attachment thumbnails are rendered by
  `ImageAttachmentAdapter` and `AttachmentHelper.calculateImageSize`.
