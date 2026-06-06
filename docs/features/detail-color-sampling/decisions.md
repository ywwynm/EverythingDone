# Detail Color Sampling And Information Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-28 - Camera colour sampling previews stay inside the dialog

DetailActivity camera colour sampling should no longer repaint the underlying
Thing Background while the sampling dialog is open. The dialog owns the live
preview state: it shows the sampled colour in its own preview strip and tints
the "Use Color" action with the sampled colour. DetailActivity commits a single
final pure-colour Thing Background only when the user accepts the sample.

## 2026-05-28 - Thing Background Information is user-facing, not diagnostic

The colour-information dialog should keep the preview and user-facing colour
values: recognised name, RGB, Hex, and HSL. It should not show the matched
dataset entry, matching method, match distance, or dataset/source row in the
main UI. Long gradient information should be height-bounded so the final action
button stays visible.

## 2026-05-27 - Camera colour sampling previews live and commits once

The Detail colour-picker camera entry should treat live camera sampling as a
preview state, not as repeated committed colour changes. While the camera
sampling dialog is open, the sampled centre colour may repaint the
DetailActivity Thing Background in real time. When the user accepts the sampled
colour, DetailActivity should commit a single final pure-colour
Thing Background and add at most one `ThingAction.UPDATE_COLOR`.

Closing or cancelling the camera sampling flow should restore the Thing
Background that was active before sampling began and should not add an undo
entry.

## 2026-05-27 - Camera-picked colour names should use a fine fixed name library

Camera-picked colours and colour-info surfaces should identify colours through
a fine-grained fixed colour-name library rather than a small algorithmic
"modifier + base colour" vocabulary. The implementation should prefer a
dataset with clear provenance, licence, and enough entries for nearest-colour
matching against arbitrary RGB samples.

Use `meodai/color-names` as the colour-name data source. The project provides a
roughly 31k-entry curated full list, MIT licence, multiple downloadable formats,
and documented nearest-colour API behaviour. Do not use `colornames.org` as the
primary bundled source despite its larger community dataset, because its
vote-driven naming quality is less predictable for an offline app UI.

For display languages, English should use the upstream `meodai/color-names`
name exactly. Simplified Chinese should use a fine-grained translated version of
that same name set; Google Translate is explicitly allowed for this bulk
colour-name translation pass. Other app locales should fall back to the English
source names until a later translation pass is requested.

## 2026-05-27 - Detail should expose current Thing Background information

DetailActivity should add an options-menu action that opens colour information
for the current Thing Background. The information surface should support pure
colours and gradients, include RGB, Hex, and HSL values, and include the colour
name source when a fixed name-library match is shown.

The colour-information action should be an overflow menu item, not an always
visible toolbar icon. It should be available from every DetailActivity state
menu, including create, underway, habit variants, finished, and deleted, because
all Thing states still have a Thing Background that can be inspected.

The camera colour-sampling entry should live in the Detail ColorPicker's bottom
action area in `COLOR_EDIT` mode, not inside the two-column colour grid. Add a
visible divider between the colour grid and the bottom tool actions so the
camera entry is read as a tool entry rather than another colour candidate. The
gradient-orientation action remains conditional on a gradient selection; the
camera entry is always available in `COLOR_EDIT`.

The camera colour-sampling dialog should use explicit actions. Live sampling is
only a preview until the user taps "Use Color". Back, outside dismissal, and
"Cancel" restore the pre-sampling Thing Background and add no undo entry. The
dialog layout should keep the rounded square live preview as the primary visual,
show the current localized/English colour name beneath it, and place compact
Cancel / Use Color actions below the name.

Use CameraX for the embedded camera colour-sampling preview. The project did
not previously have an embedded live-camera preview path, and CameraX should
own preview lifecycle, rotation, and device compatibility while an
`ImageAnalysis` pipeline samples the centre colour at a controlled rate.

Request `android.permission.CAMERA` only when the user opens the camera
colour-sampling tool. If permission is denied, close or avoid opening the
sampling dialog and show the existing Detail snackbar-style error path rather
than leaving an empty camera surface or forcing a settings deep link. Add the
manifest camera permission while keeping the existing optional camera hardware
feature declaration.

Bundle the `meodai/color-names` dataset as app data under `assets` rather than
as Android string resources. Keep English and Simplified Chinese colour names
in compact dataset files, include attribution/licence material, and load the
dataset lazily. Chinese app locales should load the translated dataset; other
locales can load the English source dataset.

Colour-name matching should prioritise perceptual accuracy. Final committed
colours and the Detail colour-information surface should use a full
CIEDE2000-style nearest-colour match against the full precomputed Lab dataset.
The live camera preview may throttle updates, average a small centre sample
region, cache recent results, and use cheaper candidate filtering to remain
smooth, but the committed result should be recomputed with the full precise
matcher.

The camera sampling dialog and the Detail colour-information surface should
share the same colour parsing/matching service and result model so the same RGB
value resolves to the same names and numeric values everywhere. Their UI
surfaces stay separate: camera sampling shows a lightweight, large live name
display, while colour information shows the full source, RGB, Hex, HSL, and
gradient breakdown.

Colour-information source attribution should be dataset-level, not per-entry.
The `meodai/color-names` distribution does not provide reliable per-colour
source metadata for each matched entry. Show the dataset source and licence,
the matching method, the matched entry name/hex, and the match distance instead
of implying that each colour name has a separate displayed source.

Lock the bundled `meodai/color-names` dataset to the exact package version or
Git commit used during implementation. Do not fetch the latest dataset at build
time. Include the locked version, download date, and MIT licence attribution in
the bundled asset metadata so colour-name matching remains stable and
user-visible names do not change unexpectedly when upstream data changes.

Implementation lock: the bundled dataset uses `color-name-list` 14.38.0,
downloaded from unpkg on 2026-05-27. Google Translate's unauthenticated endpoint
returned a reCAPTCHA block during the implementation attempt, so the shipped
asset reserves a Simplified Chinese name column but falls back to upstream
English names until a translated file or official Google Cloud Translation
credential is available.

The dataset should be stored as a plain `.tsv` asset, not `.tsv.gz`. AGP's asset
merge step expands `.gz` assets and strips the `.gz` suffix, so runtime
`AssetManager.open(...)` must target the packaged `.tsv` path.

For gradient Thing Backgrounds, the colour-information surface should show
three colour sections: gradient start colour, gradient end colour, and the
representative colour returned by `ThingBackground.representativeColor()`. Each
section should include the matched colour name plus RGB, Hex, and HSL values.
The surface should also show a gradient preview that preserves the stored
gradient orientation.
