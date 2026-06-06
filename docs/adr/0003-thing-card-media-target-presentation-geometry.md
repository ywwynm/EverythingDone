# Thing Card Media Target Presentation Geometry

Thing Card media geometry will be expressed as per-source, per-presentation media target aspect ratio plus crop, instead of separate side width percent, thumbnail source aspect ratio, media-background height ratio, and AppWidget side-media aspect hints. This keeps the user's visual intent in one model across foreground thumbnails, side panels, media backgrounds, home cards, and AppWidget projections, while still letting each surface clamp the projected target only when readability or platform limits require it.

**Consequences**

Legacy geometry fields remain readable for compatibility, but a confirmed Thing Card Appearance edit normalises the JSON into the new presentation model and omits migrated legacy fields. Presentation seeding is non-destructive: when a newly selected media presentation has no saved values, it may initialise from the previous presentation and clamp to the new presentation's guardrails, but the source presentation's draft values remain intact and nothing is durable until the user confirms.
