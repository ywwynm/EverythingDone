@file:Suppress("DEPRECATION")

package com.ywwynm.everythingdone.views

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.TextUtils
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil
import com.ywwynm.everythingdone.utils.DisplayUtil

class DrawerNavigationView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {

    sealed class ItemKey {
        data class Destination(@param:IdRes val itemId: Int) : ItemKey()
        data class Folder(val folderId: Long) : ItemKey()
    }

    data class DrawerItem(
        val key: ItemKey,
        val title: String,
        @param:DrawableRes val iconRes: Int? = null,
        val folderBackground: ThingBackground? = null,
        val folderPrivate: Boolean = false,
        val folderLevel: Int = 0,
        val hasChildFolders: Boolean = false,
        val folderExpanded: Boolean = false,
        val dividerBefore: Boolean = false,
        val groupStart: Boolean = false,
        val groupEnd: Boolean = false
    )

    private val headerView: View
    private val recyclerView: RecyclerView
    private val adapter: DrawerAdapter
    private var bottomSystemInset = 0

    private var itemClickListener: ((DrawerItem) -> Unit)? = null
    private var folderExpandClickListener: ((Long) -> Unit)? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.app_chrome_surface_elevated))

        headerView = LayoutInflater.from(context).inflate(R.layout.drawer_header, this, false)
        addView(
            headerView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        adapter = DrawerAdapter()
        recyclerView = RecyclerView(context).apply {
            layoutManager = LinearLayoutManager(context)
            setHasFixedSize(false)
            itemAnimator = DrawerTreeItemAnimator().apply {
                addDuration = 140L
                removeDuration = 140L
                moveDuration = 160L
                changeDuration = 120L
                supportsChangeAnimations = false
            }
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            this.adapter = this@DrawerNavigationView.adapter
        }
        addView(
            recyclerView,
            LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or
                        WindowInsetsCompat.Type.displayCutout()
            )
            updateBottomSystemInset(bars.bottom)
            insets
        }
    }

    fun getHeaderView(): View = headerView

    fun setOnDrawerItemClickListener(listener: (DrawerItem) -> Unit) {
        itemClickListener = listener
    }

    fun setOnFolderExpandClickListener(listener: (Long) -> Unit) {
        folderExpandClickListener = listener
    }

    fun submitItems(
        items: List<DrawerItem>,
        selectedKey: ItemKey?,
        animate: Boolean = false,
        animatedFolderToggleId: Long? = null
    ) {
        adapter.submitItems(items, selectedKey, animate, animatedFolderToggleId)
    }

    fun setSelectedKey(selectedKey: ItemKey?) {
        adapter.submitItems(adapter.currentItems(), selectedKey, true)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val availableWidth = MeasureSpec.getSize(widthMeasureSpec)
        val preferredWidth = dp(DRAWER_WIDTH_DP)
        val drawerWidth = if (availableWidth > 0) {
            minOf(preferredWidth, availableWidth)
        } else {
            preferredWidth
        }
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(drawerWidth, MeasureSpec.EXACTLY),
            heightMeasureSpec
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.requestApplyInsets(this)
    }

    private fun updateBottomSystemInset(bottom: Int) {
        if (bottomSystemInset == bottom) return
        bottomSystemInset = bottom
        adapter.updateBottomSystemInset(bottom)
    }

    private inner class DrawerAdapter : RecyclerView.Adapter<DrawerItemHolder>() {

        private var items: List<DrawerItem> = emptyList()
        private var selectedKey: ItemKey? = null
        private var animatedFolderToggleId: Long? = null
        private var bottomSystemInset = 0

        init {
            setHasStableIds(true)
        }

        fun currentItems(): List<DrawerItem> = items

        fun updateBottomSystemInset(bottom: Int) {
            if (bottomSystemInset == bottom) return
            bottomSystemInset = bottom
            if (items.isNotEmpty()) {
                notifyItemChanged(items.lastIndex)
            }
        }

        fun submitItems(
            newItems: List<DrawerItem>,
            newSelectedKey: ItemKey?,
            animate: Boolean,
            newAnimatedFolderToggleId: Long? = null
        ) {
            val oldItems = items
            val oldSelectedKey = selectedKey
            selectedKey = newSelectedKey
            animatedFolderToggleId = newAnimatedFolderToggleId
            if (!animate) {
                items = newItems.toList()
                notifyDataSetChanged()
                return
            }

            val diffResult = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize(): Int = oldItems.size

                override fun getNewListSize(): Int = newItems.size

                override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
                    return oldItems[oldItemPosition].key == newItems[newItemPosition].key
                }

                override fun areContentsTheSame(
                    oldItemPosition: Int,
                    newItemPosition: Int
                ): Boolean {
                    val oldItem = oldItems[oldItemPosition]
                    val newItem = newItems[newItemPosition]
                    return oldItem == newItem &&
                            (oldItem.key == oldSelectedKey) == (newItem.key == newSelectedKey)
                }
            })
            items = newItems.toList()
            diffResult.dispatchUpdatesTo(this)
        }

        override fun getItemId(position: Int): Long {
            return when (val key = items[position].key) {
                is ItemKey.Destination -> key.itemId.toLong()
                is ItemKey.Folder -> Long.MIN_VALUE xor key.folderId
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DrawerItemHolder {
            return DrawerItemHolder(parent.context)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: DrawerItemHolder, position: Int) {
            val item = items[position]
            holder.bind(
                item,
                item.key == selectedKey,
                shouldAnimateFolderToggle(item),
                position == items.lastIndex,
                bottomSystemInset
            )
        }

        private fun shouldAnimateFolderToggle(item: DrawerItem): Boolean {
            val folderId = (item.key as? ItemKey.Folder)?.folderId ?: return false
            if (folderId != animatedFolderToggleId) return false
            animatedFolderToggleId = null
            return true
        }
    }

    private inner class DrawerItemHolder(context: Context) :
        RecyclerView.ViewHolder(createItemView(context)) {

        private val root: LinearLayout = itemView as LinearLayout
        private val divider: View = root.getChildAt(0)
        private val groupTopSpace: View = root.getChildAt(1)
        private val content: LinearLayout = root.getChildAt(2) as LinearLayout
        private val groupBottomSpace: View = root.getChildAt(3)
        private val indent: Space = content.getChildAt(0) as Space
        private val icon: ImageView = content.getChildAt(1) as ImageView
        private val iconTitleGap: Space = content.getChildAt(2) as Space
        private val title: TextView = content.getChildAt(3) as TextView
        private val expandButton: ImageView = content.getChildAt(4) as ImageView

        fun bind(
            item: DrawerItem,
            selected: Boolean,
            animateFolderToggle: Boolean,
            isLastItem: Boolean,
            bottomSystemInset: Int
        ) {
            divider.visibility = if (item.dividerBefore) View.VISIBLE else View.GONE
            groupTopSpace.visibility = if (item.groupStart) View.VISIBLE else View.GONE
            val groupBottomHeight = if (item.groupEnd) {
                dp(DRAWER_GROUP_VERTICAL_MARGIN_DP)
            } else {
                0
            }
            val bottomInsetHeight = if (isLastItem) bottomSystemInset else 0
            val bottomSpaceHeight = groupBottomHeight + bottomInsetHeight
            groupBottomSpace.visibility = if (bottomSpaceHeight > 0) View.VISIBLE else View.GONE
            updateLinearHeight(groupBottomSpace, bottomSpaceHeight)
            content.background = createItemBackground(selected)
            content.isSelected = selected
            content.setOnClickListener { itemClickListener?.invoke(item) }

            title.text = item.title
            title.setTextColor(getDrawerItemForegroundColor())
            title.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

            val isFolder = item.key is ItemKey.Folder
            updateLinearWidth(
                indent,
                if (isFolder) {
                    (dp(DRAWER_FOLDER_INDENT_DP) * (item.folderLevel + 1))
                } else {
                    0
                }
            )
            updateLinearWidth(
                iconTitleGap,
                if (isFolder) dp(DRAWER_FOLDER_ICON_TITLE_GAP_DP) else dp(DRAWER_ITEM_ICON_TITLE_GAP_DP)
            )
            icon.setImageDrawable(createIconDrawable(item))
            updateTitleEndMargin(
                if (isFolder) {
                    dp(DRAWER_TITLE_EXPAND_MARGIN_END_DP)
                } else {
                    0
                }
            )

            if (isFolder) {
                updateLinearWidth(expandButton, dp(DRAWER_EXPAND_TOUCH_SIZE_DP))
                updateLinearEndMargin(expandButton, dp(DRAWER_EXPAND_MARGIN_END_DP))
            } else {
                updateLinearWidth(expandButton, 0)
                updateLinearEndMargin(expandButton, 0)
            }

            if (isFolder && item.hasChildFolders) {
                expandButton.visibility = View.VISIBLE
                expandButton.setImageResource(R.drawable.ic_dropdown)
                expandButton.setColorFilter(getDrawerItemForegroundColor())
                expandButton.contentDescription = context.getString(
                    if (item.folderExpanded) {
                        R.string.cd_collapse_thing_folder
                    } else {
                        R.string.cd_expand_thing_folder
                    }
                )
                BackgroundUtil.installAppChromeCircleRipple(expandButton, context)
                expandButton.animate().cancel()
                if (animateFolderToggle) {
                    expandButton.rotation = if (item.folderExpanded) 0f else 180f
                    expandButton.animate()
                        .rotation(if (item.folderExpanded) 180f else 0f)
                        .setDuration(160L)
                        .start()
                } else {
                    expandButton.rotation = if (item.folderExpanded) 180f else 0f
                }
                expandButton.setOnClickListener {
                    folderExpandClickListener?.invoke((item.key as ItemKey.Folder).folderId)
                }
            } else if (isFolder) {
                expandButton.visibility = View.INVISIBLE
                expandButton.animate().cancel()
                expandButton.setOnClickListener(null)
                expandButton.contentDescription = null
            } else {
                expandButton.visibility = View.GONE
                expandButton.animate().cancel()
                expandButton.setOnClickListener(null)
                expandButton.contentDescription = null
            }
        }

        private fun createItemBackground(selected: Boolean): Drawable {
            val pressedColor = ContextCompat.getColor(context, R.color.app_chrome_ripple)
            val selectedColor = ContextCompat.getColor(context, R.color.app_chrome_divider)
            val base = ColorDrawable(if (selected) selectedColor else Color.TRANSPARENT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                return RippleDrawable(
                    ColorStateList.valueOf(pressedColor),
                    base,
                    ColorDrawable(Color.WHITE)
                )
            }

            return StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(pressedColor))
                addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(selectedColor))
                addState(intArrayOf(), base)
            }
        }

        private fun createIconDrawable(item: DrawerItem): Drawable? {
            val folderBackground = item.folderBackground
            if (folderBackground != null) {
                return FolderIconDrawable(folderBackground, item.folderPrivate)
            }

            val iconRes = item.iconRes ?: return null
            val drawable = AppCompatResources.getDrawable(context, iconRes) ?: return null
            return DisplayUtil.opaqueTintDrawable(
                context,
                drawable,
                getDrawerItemForegroundColor()
            )
        }

        private fun getDrawerItemForegroundColor(): Int {
            return ContextCompat.getColor(context, R.color.app_chrome_drawer_item_foreground)
        }

        private fun updateTitleEndMargin(marginEnd: Int) {
            updateLinearEndMargin(title, marginEnd)
        }

        private fun updateLinearEndMargin(view: View, marginEnd: Int) {
            val params = view.layoutParams as LinearLayout.LayoutParams
            if (params.marginEnd != marginEnd || params.rightMargin != marginEnd) {
                params.marginEnd = marginEnd
                params.rightMargin = marginEnd
                view.layoutParams = params
            }
        }

        private fun updateLinearWidth(view: View, width: Int) {
            val params = view.layoutParams as LinearLayout.LayoutParams
            if (params.width != width) {
                params.width = width
                view.layoutParams = params
            }
        }

        private fun updateLinearHeight(view: View, height: Int) {
            val params = view.layoutParams as LinearLayout.LayoutParams
            if (params.height != height) {
                params.height = height
                view.layoutParams = params
            }
        }
    }

    private fun createItemView(context: Context): View {
        val density = context.resources.displayMetrics.density
        val root = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = RecyclerView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        root.addView(
            View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.app_chrome_divider))
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (density * 1f).toInt().coerceAtLeast(1)
            )
        )

        root.addView(
            Space(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(DRAWER_GROUP_VERTICAL_MARGIN_DP)
            )
        )

        val content = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            setPaddingRelative(dp(DRAWER_ITEM_START_PADDING_DP), 0, 0, 0)
        }
        root.addView(
            content,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(DRAWER_ITEM_HEIGHT_DP)
            )
        )

        content.addView(
            Space(context),
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT)
        )
        content.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
            },
            LinearLayout.LayoutParams(
                dp(DRAWER_ICON_SIZE_DP),
                dp(DRAWER_ICON_SIZE_DP)
            )
        )
        content.addView(
            Space(context),
            LinearLayout.LayoutParams(
                dp(DRAWER_ITEM_ICON_TITLE_GAP_DP),
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        content.addView(
            TextView(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                setSingleLine(true)
                ellipsize = TextUtils.TruncateAt.END
                textSize = 16f
                includeFontPadding = true
            },
            LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            )
        )
        content.addView(
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER
                val padding = dp(DRAWER_EXPAND_ICON_PADDING_DP)
                setPadding(padding, padding, padding, padding)
                isClickable = true
                isFocusable = true
            },
            LinearLayout.LayoutParams(0, dp(DRAWER_EXPAND_TOUCH_SIZE_DP))
        )

        root.addView(
            Space(context),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(DRAWER_GROUP_VERTICAL_MARGIN_DP)
            )
        )

        return root
    }

    private fun dp(value: Float): Int {
        return (resources.displayMetrics.density * value).toInt()
    }

    private class DrawerTreeItemAnimator : DefaultItemAnimator() {

        private val runningAdds = HashSet<RecyclerView.ViewHolder>()
        private val runningRemoves = HashSet<RecyclerView.ViewHolder>()

        override fun animateAdd(holder: RecyclerView.ViewHolder): Boolean {
            val view = holder.itemView
            view.animate().cancel()
            view.alpha = 0f
            view.translationY = -view.resources.displayMetrics.density * 12f
            runningAdds.add(holder)
            dispatchAddStarting(holder)
            view.animate()
                .alpha(1f)
                .translationY(0f)
                .setDuration(addDuration)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        finishAdd(holder)
                    }
                })
                .start()
            return true
        }

        override fun animateRemove(holder: RecyclerView.ViewHolder): Boolean {
            val view = holder.itemView
            view.animate().cancel()
            runningRemoves.add(holder)
            dispatchRemoveStarting(holder)
            view.animate()
                .alpha(0f)
                .translationY(-(view.height.coerceAtLeast(1) * 0.35f))
                .setDuration(removeDuration)
                .setListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        finishRemove(holder)
                    }
                })
                .start()
            return true
        }

        override fun endAnimation(item: RecyclerView.ViewHolder) {
            item.itemView.animate().cancel()
            finishAdd(item)
            finishRemove(item)
            super.endAnimation(item)
        }

        override fun endAnimations() {
            for (holder in runningAdds.toList()) {
                holder.itemView.animate().cancel()
                finishAdd(holder)
            }
            for (holder in runningRemoves.toList()) {
                holder.itemView.animate().cancel()
                finishRemove(holder)
            }
            super.endAnimations()
        }

        override fun isRunning(): Boolean {
            return runningAdds.isNotEmpty() || runningRemoves.isNotEmpty() || super.isRunning()
        }

        private fun finishAdd(holder: RecyclerView.ViewHolder) {
            if (!runningAdds.remove(holder)) return
            holder.itemView.animate().setListener(null)
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
            dispatchAddFinished(holder)
            dispatchFinishedIfDone()
        }

        private fun finishRemove(holder: RecyclerView.ViewHolder) {
            if (!runningRemoves.remove(holder)) return
            holder.itemView.animate().setListener(null)
            holder.itemView.alpha = 1f
            holder.itemView.translationY = 0f
            dispatchRemoveFinished(holder)
            dispatchFinishedIfDone()
        }

        private fun dispatchFinishedIfDone() {
            if (!isRunning) {
                dispatchAnimationsFinished()
            }
        }
    }

    private class FolderIconDrawable(
        private val background: ThingBackground,
        private val privateFolder: Boolean
    ) : Drawable() {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }
        private val lockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val path = Path()
        private val lockPath = Path()
        private val lockRect = RectF()
        private var externalAlpha = 255

        override fun draw(canvas: Canvas) {
            if (bounds.width() <= 0 || bounds.height() <= 0) return

            val size = minOf(bounds.width(), bounds.height()).toFloat()
            val left = bounds.left + (bounds.width() - size) / 2f
            val top = bounds.top + (bounds.height() - size) / 2f

            if (background.mode == ThingBackground.Mode.PURE) {
                paint.shader = null
                paint.color = background.color
            } else {
                paint.shader = createGradientShader(left, top, size)
            }
            paint.alpha = externalAlpha

            buildFolderPath(left, top, size)
            canvas.drawPath(path, paint)
            if (privateFolder) {
                drawLock(canvas, left, top, size)
            }
        }

        override fun setAlpha(alpha: Int) {
            externalAlpha = alpha.coerceIn(0, 255)
            invalidateSelf()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            lockPaint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Suppress("OVERRIDE_DEPRECATION")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = -1

        override fun getIntrinsicHeight(): Int = -1

        private fun buildFolderPath(left: Float, top: Float, size: Float) {
            fun x(value: Float): Float = left + value / 24f * size
            fun y(value: Float): Float = top + value / 24f * size

            path.reset()
            path.moveTo(x(3f), y(6.5f))
            path.cubicTo(x(3f), y(5.12f), x(4.12f), y(4f), x(5.5f), y(4f))
            path.lineTo(x(9.4f), y(4f))
            path.cubicTo(x(10.07f), y(4f), x(10.72f), y(4.27f), x(11.19f), y(4.75f))
            path.lineTo(x(12.44f), y(6f))
            path.lineTo(x(18.5f), y(6f))
            path.cubicTo(x(19.88f), y(6f), x(21f), y(7.12f), x(21f), y(8.5f))
            path.lineTo(x(21f), y(17.5f))
            path.cubicTo(x(21f), y(18.88f), x(19.88f), y(20f), x(18.5f), y(20f))
            path.lineTo(x(5.5f), y(20f))
            path.cubicTo(x(4.12f), y(20f), x(3f), y(18.88f), x(3f), y(17.5f))
            path.lineTo(x(3f), y(6.5f))
            path.close()
        }

        private fun drawLock(canvas: Canvas, left: Float, top: Float, size: Float) {
            fun x(value: Float): Float = left + value / 24f * size
            fun y(value: Float): Float = top + value / 24f * size

            lockPaint.shader = null
            lockPaint.color = if (BackgroundUtil.isLight(background.representativeColor())) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            lockPaint.alpha = (externalAlpha * 0.82f).toInt().coerceIn(0, 255)

            lockPaint.style = Paint.Style.STROKE
            lockPaint.strokeWidth = (size * 0.72f / 24f).coerceAtLeast(1f)
            lockPath.reset()
            lockPath.moveTo(x(10.35f), y(12.9f))
            lockPath.lineTo(x(10.35f), y(11.95f))
            lockPath.cubicTo(x(10.35f), y(10.72f), x(11.04f), y(10.02f), x(12f), y(10.02f))
            lockPath.cubicTo(x(12.96f), y(10.02f), x(13.65f), y(10.72f), x(13.65f), y(11.95f))
            lockPath.lineTo(x(13.65f), y(12.9f))
            canvas.drawPath(lockPath, lockPaint)

            lockPaint.style = Paint.Style.FILL
            lockRect.set(x(9.5f), y(12.7f), x(14.5f), y(16.45f))
            val corner = size * 0.8f / 24f
            canvas.drawRoundRect(lockRect, corner, corner, lockPaint)
        }

        private fun createGradientShader(left: Float, top: Float, size: Float): LinearGradient {
            val right = left + size
            val bottom = top + size
            val coords = when (background.orientation) {
                ThingBackground.Orientation.L_R -> floatArrayOf(left, top, right, top)
                ThingBackground.Orientation.T_B -> floatArrayOf(left, top, left, bottom)
                ThingBackground.Orientation.LT_RB -> floatArrayOf(left, top, right, bottom)
                ThingBackground.Orientation.RT_LB -> floatArrayOf(right, top, left, bottom)
                ThingBackground.Orientation.LB_RT -> floatArrayOf(left, bottom, right, top)
                ThingBackground.Orientation.RB_LT -> floatArrayOf(right, bottom, left, top)
                ThingBackground.Orientation.R_L -> floatArrayOf(right, top, left, top)
                ThingBackground.Orientation.B_T -> floatArrayOf(left, bottom, left, top)
            }
            return LinearGradient(
                coords[0],
                coords[1],
                coords[2],
                coords[3],
                background.color,
                background.endColor,
                Shader.TileMode.CLAMP
            )
        }
    }

    companion object {
        private const val DRAWER_ITEM_HEIGHT_DP = 48.0f
        private const val DRAWER_GROUP_VERTICAL_MARGIN_DP = 8.0f
        private const val DRAWER_WIDTH_DP = 320.0f
        private const val DRAWER_ITEM_START_PADDING_DP = 16.0f
        private const val DRAWER_ICON_SIZE_DP = 24.0f
        private const val DRAWER_ITEM_ICON_TITLE_GAP_DP = 32.0f
        private const val DRAWER_FOLDER_ICON_TITLE_GAP_DP = 16.0f
        private const val DRAWER_FOLDER_INDENT_DP = 16.0f
        private const val DRAWER_TITLE_EXPAND_MARGIN_END_DP = 8.0f
        private const val DRAWER_EXPAND_TOUCH_SIZE_DP = 40.0f
        private const val DRAWER_EXPAND_MARGIN_END_DP = 8.0f
        private const val DRAWER_EXPAND_ICON_PADDING_DP = 8.0f
    }
}
