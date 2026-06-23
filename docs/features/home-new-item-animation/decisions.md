# Decisions

## 2026-06-23 - 创建返回后先把新记事平滑滚到完整可见再播放入场动画

问题：创建新记事返回列表后，新记事插在置顶区（sticky 文件夹/记事）之后。正常插入路径（`ThingsActivity.updateMainUiForCreateDone`）只做了 `armNewItemAnimation` + `notifyItemInserted(newListPosition)`，**没有滚动到新位置**；而入场动画是在该 ViewHolder 被绑定且完成布局后才触发（`ThingsAdapter.maybeTriggerArmedNewItemAnimation`）。新记事若落在屏幕外就一直不被绑定，动画挂起，要用户手动下滑才触发；shining 风格触发时还会 `stopScroll()` 并由触摸监听（`setRecyclerViewEvents`）拦截滑动，于是“滑到大概能看到就被掐断、看不全”。

决策：播放入场动画前，先把新记事**平滑滚动到完整可见**，再播放。

- 落点：新记事顶部对齐到折叠后**工具栏正下方**（snap-to-start + 工具栏高度偏移），让它成为焦点，置顶区暂时滚出顶部。
- 用**平滑滚动**（非瞬移），动作连贯。
- 关键门控：把动画触发从“一绑定就播”改为“**滚动停下（IDLE）后再播**”，避免平滑滚动途中新卡片刚进视口即被绑定触发、以及 shining 风格 `stopScroll` 把平滑滚动半路掐断。
- **揭示（circular reveal）与 shining 描边两种风格都套用**该“先滚到位再播”。
- 动画演完**停在新记事处、不回弹到顶部**（最新记事停在工具栏下方）。
- 若新记事**本来就完整可见**（如当前无置顶项、已在顶部），不滚动、原地播放。
- 不在范围：创建时若开着自定义类型筛选，返回会把类型筛选重置回全部类型并触发整列表重载（`typeFilterWasReset` 分支），该路径本就没有逐项入场动画，维持现状。

## 2026-06-06 - Guard item-scoped ShiningBorder geometry during new-item animation

Use a short-term guard for the home new-item `ShiningBorder` animation instead
of locking device orientation. The card-scoped border path is computed from the
card and overlay window coordinates before playback, so RecyclerView scrolling,
configuration changes, search/filter refreshes, undo operations, and delayed
thing updates can invalidate that geometry.

The guard starts when the new card is hidden for the pending `ShiningBorder`
animation, consumes RecyclerView/item touch while active, stops ongoing scroll,
and finishes the new-item border immediately before known list geometry changes.
Finishing restores the target card to `VISIBLE`, hides and resets the border,
and clears the general reveal-animation flag.

## 2026-06-06 - Card-level new-item reveal is lower risk than ShiningBorder

There are two reveal-style animations in `ThingsActivity`:

- The FAB-to-detail create animation uses the global `RevealLayout` overlay and
  a window-coordinate center from the FAB. It runs before the thing is created
  and is separate from the new-card entry animation.
- The post-create new-card reveal uses
  `ViewAnimationUtils.createCircularReveal()` directly on the card view, with
  local card coordinates.

The post-create card reveal does not need the same geometry guard as the
item-scoped `ShiningBorder` because it is attached to the card view rather than
to an overlay path computed from window coordinates. If the card moves, the
reveal moves with it. It may still benefit from a smaller cleanup later:
tracking the pending 180ms delay and active card animator so geometry changes
can restore the card to `VISIBLE` consistently.
