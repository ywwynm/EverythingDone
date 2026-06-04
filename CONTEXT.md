# EverythingDone

EverythingDone is a personal task and note application where each thing carries its own colour identity while app chrome provides the surrounding navigation and settings experience.

## Language

**Thing**:
A user-created note, task, reminder, habit, goal, or related item whose own background colour is part of its identity.
_Avoid_: note as the blanket term for all things

**Thing Background**:
The colour or gradient owned by a Thing and used as the highest-priority visual background when displaying that Thing.
_Avoid_: app theme colour, page background

**Thing Background Information**:
A user-facing description of a Thing Background's colour identity, including its recognised colour name and numeric colour values.
_Avoid_: app theme information, debug colour data

**Thing Foreground**:
Text, icons, and other adaptive foreground content drawn directly on top of a Thing Background or Thing Card Media Background. Its colour is chosen from the visible Thing-owned background and does not depend on Appearance Mode.
_Avoid_: dark-mode foreground

**Thing Card**:
A compact card representation of a Thing, used by the home list and embedded single-card surfaces such as Doing and noticeable reminder surfaces. Its layout may differ from the Detail screen while preserving the Thing's identity.
_Avoid_: note card as the blanket term

**Full-Span Thing Card**:
A Thing Card that is intentionally presented with a wider card span as a persistent presentation preference of that Thing.
_Avoid_: temporary wide row, per-filter layout state

**Thing Card Span Mode**:
The persistent presentation choice that determines whether a Thing Card uses normal span or full span in surfaces that support wider cards.
_Avoid_: complete layout style, image placement mode

**Thing Card Appearance**:
The set of persistent presentation choices that control how a Thing Card is visually arranged and how its media is shown.
_Avoid_: media settings only, card content editing

**Thing Card Appearance Update Time**:
The time when a Thing's Thing Card Appearance was last changed.
_Avoid_: content update time, reminder update time

**Thing Card Image Placement**:
The persistent presentation choice that determines where Thing Card Media appears within a Thing Card relative to the card's other visible content.
_Avoid_: attachment order, image crop ratio, image crop focus

**Thing Card Media**:
The image or video thumbnail used as the visual media for a Thing Card.
_Avoid_: image-only card thumbnail, attachment preview as a blanket term

**Thing Card Media Source**:
The persistent presentation choice that determines which image or video attachment provides Thing Card Media.
_Avoid_: first attachment, attachment order

**Thing Card Video Frame**:
The video frame selected to provide Thing Card Media for a video attachment.
_Avoid_: playback position, video attachment itself

**Thing Card Media Crop**:
The persistent presentation choice that determines the crop center, user zoom, and when applicable source crop shape of Thing Card Media.
_Avoid_: image placement, centerCrop, editing the attachment file

**Thing Card Media Target**:
The area of a Thing Card where Thing Card Media is drawn.
_Avoid_: source crop, attachment file size

**Thing Card Thumbnail Crop**:
The Thing Card Media Crop used when Thing Card Media is displayed as a separate thumbnail region within a Thing Card.
_Avoid_: background crop, image placement

**Thing Card Media Background Crop**:
The Thing Card Media Crop used when Thing Card Media is displayed as a Thing Card Media Background.
_Avoid_: thumbnail crop, replacing the Thing Background

**Thing Card Media Background**:
A Thing Card presentation choice where Thing Card Media is drawn as the card's visual background behind Thing Foreground.
_Avoid_: app background image, replacing the Thing

**Thing Card Media Background Mask**:
The overlay that sits on top of Thing Card Media Background so Thing Foreground remains readable.
_Avoid_: app dark overlay, selection cover

**Thing Card Side Media Width**:
The persistent presentation choice that determines how much horizontal space Thing Card Media uses in a side-by-side Thing Card layout.
_Avoid_: crop ratio, content width as media crop

**App Chrome**:
The surrounding interface outside a Thing Background, including home, settings, help, popups, dialogs, drawers, and other navigation or configuration surfaces.
_Avoid_: thing UI

**Thing Background Surface**:
A screen whose primary visual identity comes from a Thing Background rather than App Chrome.
_Avoid_: dark-mode screen

**Hybrid Chrome Surface**:
A surface that wraps Thing-owned content in App Chrome, such as a reminder dialog whose shell should follow Appearance Mode while the embedded Thing keeps its Thing Background.
_Avoid_: treating the whole surface as either pure chrome or pure thing UI

**Appearance Mode**:
The user's light/dark preference for App Chrome, where following the system setting takes priority over a manual dark-mode choice.
_Avoid_: independent dark-mode booleans

**Button-like Control**:
A local command control that represents one action without occupying an entire row or card.
_Avoid_: full-row clickable surface, full-card clickable surface, full-row action surface

## Relationships

- A **Thing** has one **Thing Background**.
- A **Thing Background** can be described by **Thing Background Information**.
- A **Thing Background** overrides **Appearance Mode** for Thing-owned surfaces.
- A **Thing Foreground** adapts to the visible Thing-owned background, not to **Appearance Mode**.
- On ordinary Thing-owned surfaces, **Thing Foreground** adapts to **Thing Background**.
- On **Thing Card Media Background**, **Thing Foreground** adapts to the masked media background.
- A **Thing** has one **Thing Card** presentation preference that can be reused by card-based surfaces.
- A **Full-Span Thing Card** is a presentation preference of a **Thing**, not of a home-list filter.
- **Thing Card Appearance** includes **Thing Card Span Mode**, **Thing Card Image Placement**, **Thing Card Media Source**, **Thing Card Media Crop**, and **Thing Card Media Background**.
- **Thing Card Appearance** may include **Thing Card Side Media Width** when Thing Card Media is placed left or right.
- A **Thing** may have one **Thing Card Appearance Update Time**.
- A **Thing Card** has one **Thing Card Span Mode**.
- A **Thing Card** may have one **Thing Card Image Placement** when the Thing has Thing Card Media.
- A **Thing Card** may have one **Thing Card Media Source** when the Thing has image or video attachments.
- A video **Thing Card Media Source** may have one **Thing Card Video Frame**.
- **Thing Card Video Frame** changes Thing Card presentation only and does not change video playback.
- A **Thing Card** may have one **Thing Card Media Crop** when the Thing has Thing Card Media.
- **Thing Card Media Crop** is applied to a **Thing Card Media Target**.
- **Thing Card Media Crop** and **Thing Card Video Frame** belong to a **Thing Card Media Source**.
- **Thing Card Thumbnail Crop** and **Thing Card Media Background Crop** can both adjust crop center and user zoom, but they apply to different Thing Card media presentations.
- **Thing Card Media Crop** changes Thing Card presentation only and does not modify the underlying attachment file.
- A **Thing Card** may use **Thing Card Media Background** when the Thing has Thing Card Media.
- A **Thing Card Media Background** may have one **Thing Card Media Background Mask**.
- **Thing Card Media Background Mask** belongs to a **Thing Card Media Source**.
- **Thing Card Media Background** does not replace a Thing's **Thing Background**.
- **Thing Card** presentation choices are shared card preferences, not home-list-only preferences.
- Hidden private **Thing Cards** do not expose **Thing Card Media**.
- **Appearance Mode** applies to **App Chrome**.
- A **Button-like Control** can appear on **App Chrome** or directly on a **Thing Background**.
- **Thing Background Surfaces** do not recreate solely because **Appearance Mode** changes.
- **Hybrid Chrome Surfaces** apply **Appearance Mode** to their chrome shell, icons, and controls, while embedded Thing content continues to use its **Thing Background**.
- New installs and upgrades default to light App Chrome unless the user explicitly enables follow-system or forced dark Appearance Mode.
- Light App Chrome is compatibility-sensitive: dark-mode infrastructure must not change existing light-mode visuals.

## Example Dialogue

> **Dev:** "When dark mode is enabled, should a red reminder thing become dark?"
> **Domain expert:** "No - the reminder keeps its Thing Background; only the App Chrome around it changes."

## Flagged Ambiguities

- "Dark mode settings" could mean two independent booleans; resolved as **Appearance Mode**, where follow-system has priority and disables manual dark-mode selection.
- "Button" can mean either a local command control or an entire clickable row/card; resolved as **Button-like Control** for local command controls only.
- "Note card" can mean only a Note-type Thing or any card representation; resolved as **Thing Card** when discussing shared card presentation.
- "Home card" can mean a card shown only in the home list or a shared card presentation preference; resolved as **Thing Card** for reusable card presentation choices.
- "Image thumbnail" can exclude video thumbnails; resolved as **Thing Card Media** when discussing image or video thumbnails used by Thing Cards.
- "Card background image" can mean replacing the Thing's identity background or only changing a card presentation; resolved as **Thing Card Media Background**, which does not replace **Thing Background**.
- "First attachment" can mean the first stored attachment or the card's chosen media source; resolved as **Thing Card Media Source** when discussing which attachment a Thing Card uses.
- "Card media settings" can be too narrow when the same entry also controls span and image placement; resolved as **Thing Card Appearance** for the whole card-presentation editor.
- "update time" can mean content changes or card appearance changes; resolved as **Thing Card Appearance Update Time** when only Thing Card Appearance changed.
