# Detail Attachment Media Appearance

Detail attachment media presentation will use a separate
`detail_attachment_media_appearance` Thing-level JSON field instead of extending
`thing_card_appearance`. This keeps Detail attachment thumbnails independent
from Thing Card, widget, and notification projections while still allowing the
implementation to reuse the same media target aspect-ratio and crop math.

The model stores appearance per image/video attachment source, keyed by the
existing `typePathName`, with separate `grid` and `fullSpan` presentations. This
prevents attachment reordering from transferring crop or full-span settings
between files, and it lets Detail keep its current legacy layout until the user
