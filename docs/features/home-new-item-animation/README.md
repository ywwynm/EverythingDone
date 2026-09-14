# Home New Item Animation

Tracks the home-screen animation shown after creating a new thing, especially
the item-scoped `ShiningBorder` path drawn over the newly inserted card.

## Status

- 2026-09-13：三档动画选择及新建页面衔接移至 [新建记事动画](../thing-creation-animation/README.md) 维护；保存后的粒子入场继续复用本目录记录的滚动门控与几何清理。

- Active: short-term geometry guard implemented on 2026-06-06.

## Documents

- `decisions.md` - feature-specific decisions.
- `followups.md` - deferred technical improvements.
- `sessions.md` - implementation history.

## Related Code

- `app/src/main/java/com/ywwynm/everythingdone/activities/ThingsActivity.kt`
- `app/src/main/java/com/ywwynm/everythingdone/adapters/ThingsAdapter.kt`
- `app/src/main/java/com/ywwynm/everythingdone/views/reveal/ShiningBorder.kt`
