package com.ywwynm.everythingdone.views

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.ThingBackground
import com.ywwynm.everythingdone.utils.BackgroundUtil

/**
 * 一些跨界面共用、需要统一管理的颜色来源。改这里即可多处同步调整。
 */
object ColorConstants {

    /**
     * 三处「文件夹列表区域」（[DrawerNavigationView] 抽屉、移动到文件夹 dialog、记事列表 widget 配置）
     * 共用的、相对固定的配色。
     *
     * 这里只放「不随单个文件夹颜色变化」的固定色，以及虽由文件夹底色推导、但「推导规则 / 透明度固定」
     * 的对比色 / 波纹色。文件夹自身颜色（未选中行的图标、波纹、选中行的实色填充）仍由各行的
     * [ThingBackground] 直接给出，不在此处。
     */
    object FolderList {

        /**
         * 未选中行的前景色：文件夹 / 「全部」图标 tint、名称、展开收缩箭头都用它（展开箭头的源 PNG
         * 自带 ~54% alpha，叠加后更淡）。
         */
        @ColorInt
        @JvmStatic
        fun unselectedForeground(context: Context): Int =
            ContextCompat.getColor(context, R.color.app_chrome_drawer_item_foreground)

        /** 行不可选（如移动到文件夹 dialog 的禁止目标）时的前景色。 */
        @ColorInt
        @JvmStatic
        fun disabledForeground(context: Context): Int =
            ContextCompat.getColor(context, R.color.app_chrome_on_surface_disabled)

        /**
         * 选中行（已铺文件夹实色）上的前景对比色：名称、文件夹图标、「全部」图标。按底色明暗自适应
         * （亮底偏黑、暗底偏白），透明度 [BackgroundUtil.ON_ALPHA_PRIMARY]。
         */
        @ColorInt
        @JvmStatic
        fun selectedForeground(bg: ThingBackground): Int =
            BackgroundUtil.onColor(bg, BackgroundUtil.ON_ALPHA_PRIMARY)

        /**
         * 选中行展开 / 收缩箭头的满不透明对比色（ic_dropdown 源 PNG 仅 ~54% alpha，需配合
         * opaqueTintDrawable 重映射为满不透明）。
         */
        @ColorInt
        @JvmStatic
        fun selectedExpandIcon(bg: ThingBackground): Int =
            BackgroundUtil.onColor(bg, 1f)

        /** 选中行（已铺实色）触摸波纹的自适应色：亮底偏黑、暗底 / accent 偏白。 */
        @ColorInt
        @JvmStatic
        fun selectedRipple(bg: ThingBackground): Int =
            BackgroundUtil.adaptiveRippleColor(bg)
    }
}
