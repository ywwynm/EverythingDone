# Home Empty State Execution

## Checklist

- [x] Add first-use history storage and migration cleanup for legacy
  placeholders.
- [x] Add model-level helpers for real content, legacy placeholders, and
  concrete type matching.
- [x] Remove placeholder creation from new database initialization and normal
  DAO mutation paths.
- [x] Remove transient/generated `NOTIFY_EMPTY` list entries from
  `ThingManager`.
- [x] Keep structurally empty Thing Folders instead of auto-deleting them.
- [x] Add the home empty-state view to the main list layout.
- [x] Implement empty-guidance selection in `ThingsActivity`.
- [x] Replace old placeholder-change UI paths with ordinary list refresh plus
  empty-state refresh.
- [x] Add ordinary empty and empty-Folder strings.
- [x] Update widgets/remote-action paths that still assume placeholders exist.
- [x] Run build verification and record results.

## Verification Results

- `.\gradlew.bat :app:assembleDebug` passed after implementation.
- `git diff --check` reported no whitespace errors; it only printed the
  repository's existing LF-to-CRLF working-copy warnings.

## Implementation Notes

- Migration must initialize first-use history before deleting legacy placeholder
  rows, otherwise upgraded users with existing content could see first-use
  welcome guidance again.
- Operation-result empty guidance is intentionally Activity-local and should
  not be persisted across process death or projection changes.
- The list's source of truth should be "visible entries" rather than synthetic
  placeholder rows. Empty UI belongs beside the list, not inside the adapter.
- Folder entries count as user content in their parent projection, even when the
  Folder itself is empty.
- Opened Empty Thing Folders need their own guidance because they are a valid
  container state, not an error or a deleted object.
