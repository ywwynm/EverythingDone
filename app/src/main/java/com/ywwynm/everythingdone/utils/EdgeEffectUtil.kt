package com.ywwynm.everythingdone.utils

import android.graphics.Color
import androidx.viewpager.widget.ViewPager
import androidx.recyclerview.widget.RecyclerView
import android.widget.EdgeEffect
import android.widget.ScrollView

import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Created by ywwynm on 2015/8/20.
 * Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.
 * Util to set over-scroll color for scrollable views like RecyclerView, ViewPager,
 * ScrollView and etc.
 */
object EdgeEffectUtil {

    const val TAG: String = "EdgeEffectUtil"

    @JvmStatic
    fun getEdgeColorDark(): Int {
        return Color.parseColor("#40000000")
    }

    @JvmStatic
    fun forScrollView(sv: ScrollView?, color: Int) {
        try {
            val clazz: Class<*> = ScrollView::class.java
            val fEdgeGlowTop: Field = clazz.getDeclaredField("mEdgeGlowTop")
            val fEdgeGlowBottom: Field = clazz.getDeclaredField("mEdgeGlowBottom")
            fEdgeGlowTop.isAccessible = true
            fEdgeGlowBottom.isAccessible = true
            setEdgeEffectColor(fEdgeGlowTop.get(sv) as EdgeEffect?, color)
            setEdgeEffectColor(fEdgeGlowBottom.get(sv) as EdgeEffect?, color)
        } catch (ignored: Exception) {
        }
    }

    @JvmStatic
    fun forRecyclerView(recyclerView: RecyclerView?, color: Int) {
        try {
            val rvClass: Class<*> = RecyclerView::class.java
            for (name in arrayOf("ensureTopGlow", "ensureBottomGlow")) {
                val method: Method = rvClass.getDeclaredMethod(name)
                method.isAccessible = true
                method.invoke(recyclerView)
            }
            for (name in arrayOf("mTopGlow", "mBottomGlow")) {
                val field: Field = rvClass.getDeclaredField(name)
                field.isAccessible = true
                val edge: Any = field.get(recyclerView)!!
                val fEdgeEffect: Field = edge.javaClass.getDeclaredField("mEdgeEffect")
                fEdgeEffect.isAccessible = true
                setEdgeEffectColor(fEdgeEffect.get(edge) as EdgeEffect?, color)
            }
        } catch (ignored: Exception) { }
    }

    @JvmStatic
    fun forViewPager(viewPager: ViewPager?, color: Int) {
        try {
            val vpClass: Class<*> = ViewPager::class.java
            for (name in arrayOf("mLeftEdge", "mRightEdge")) {
                val field: Field = vpClass.getDeclaredField(name)
                field.isAccessible = true
                val edge: Any = field.get(viewPager)!!
                val fEdgeEffect: Field = edge.javaClass.getDeclaredField("mEdgeEffect")
                fEdgeEffect.isAccessible = true
                setEdgeEffectColor(fEdgeEffect.get(edge) as EdgeEffect?, color)
            }
        } catch (ignored: Exception) { }
    }

    private fun setEdgeEffectColor(edgeEffect: EdgeEffect?, color: Int) {
        try {
            edgeEffect!!.color = color
        } catch (ignored: Exception) { }
    }

}
