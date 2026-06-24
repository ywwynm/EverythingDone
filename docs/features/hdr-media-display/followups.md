# HDR Media Display — Follow-ups

## Open

### Video attachments may reference an original public path (not an app copy)

Surfaced while root-causing the video-play crash (2026-06-24): a video
attachment stored the path `/storage/emulated/0/DCIM/Camera/VID....mp4` — the
camera roll's original location, not a copy under the app's files dir. Images
and newer imports go through `FileUtil.copyUriToFile` (raw byte copy into the
app dir), so this looks like older data or a path that bypassed the copy.

Risk if widespread: such attachments break when the user deletes/moves the
original media, and may be missing from app backups. The crash itself is fixed
(FileProvider now covers the external root + guarded), and `READ_MEDIA_VIDEO`
lets the app open it, so playback works for now.

Possible follow-up (needs a product decision, out of current scope):
- Decide whether all video attachments should be copied into the app dir on
  import (like images), and/or migrate existing external-path references.
- Confirm backup/export (`ThingExporter`, `BackupHelper`) handles external-path
  attachments correctly.

Deferred — not part of the HDR feature; raise with the user before acting.
