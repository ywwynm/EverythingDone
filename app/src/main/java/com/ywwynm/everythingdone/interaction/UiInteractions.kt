package com.ywwynm.everythingdone.interaction

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import androidx.appcompat.view.WindowCallbackWrapper
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.ywwynm.everythingdone.R
import java.util.WeakHashMap

/** 弹窗、菜单与 Activity 启动共用的受理入口，不使用固定毫秒数的点击冷却。 */
object UiInteractions {
    private val handler = Handler(Looper.getMainLooper())
    private val sessions = WeakHashMap<Activity, Session>()
    private val managers = WeakHashMap<FragmentManager, Session>()
    private class Origin(val token: Any)
    internal class Session {
        val state = UiTransitionState()
        var origin: Any? = null
        val roots = mutableMapOf<Any, Pair<View, Int>>()
        val results = mutableMapOf<Int, Any>()
        fun source(): Any = (origin as? Origin)?.token ?: state.current
        fun refresh() {
            roots.forEach { (token, entry) ->
                entry.first.importantForAccessibility = if (state.canInteract(token)) entry.second
                    else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            }
        }
    }

    class Modal internal constructor(private val session: Session, private val token: Any) {
        private var released = false
        fun ready() { session.state.ready(token); session.refresh() }
        fun closing() { session.state.closing(token); session.refresh() }
        /** 留到当前关闭回调及同步后继请求执行后释放，不引入计时冷却。 */
        fun finish() { closing(); handler.post { release() } }
        fun release() {
            if (released) return
            released = true
            session.state.release(token)
            session.roots.remove(token)?.let { it.first.importantForAccessibility = it.second }
            session.refresh()
        }
        fun attach(window: Window) { installWindow(window, session, token) }
        fun attach(view: View) {
            session.roots.putIfAbsent(token, view to view.importantForAccessibility)
            session.refresh()
        }
        fun inputDispatcher(): InputDispatcher = InputDispatcher(session, token)
    }

    fun attach(activity: Activity) {
        activity.theme.applyStyle(R.style.UiInteractionPolicy, true)
        val session = sessions.getOrPut(activity) { Session() }
        if (activity is FragmentActivity) managers[activity.supportFragmentManager] = session
        installWindow(activity.window, session, session.state.root)
    }

    fun paused(activity: Activity) { sessions[activity]?.state?.paused() }
    fun resumed(activity: Activity) { sessions[activity]?.let { it.state.resumed(); it.refresh() } }
    fun destroyed(activity: Activity) {
        sessions.remove(activity)?.let { session ->
            managers.entries.removeAll { it.value === session }
            // Activity.recreate() 可以保留 DecorView，临时无障碍标记不能带进新会话。
            session.roots.values.forEach { it.first.importantForAccessibility = it.second }
            session.roots.clear()
        }
    }

    private fun session(activity: Activity): Session {
        if (sessions[activity] == null) attach(activity)
        return sessions.getValue(activity)
    }

    fun open(manager: FragmentManager, key: String): Modal? {
        if (manager.isStateSaved || manager.isDestroyed) return null
        val session = managers[manager] ?: manager.fragments.firstOrNull()?.activity?.let(::session)
            ?: return null
        return open(session, key)
    }

    fun open(activity: Activity, key: String): Modal? =
        if (activity.isFinishing || activity.isDestroyed) null else open(session(activity), key)

    private fun open(session: Session, key: String): Modal? {
        val token = session.state.open(key, session.source()) ?: return null
        session.refresh()
        return Modal(session, token)
    }

    fun restore(activity: Activity, key: String): Modal {
        val session = session(activity)
        return Modal(session, session.state.restore(key)).also { session.refresh() }
    }

    fun launch(activity: Activity, requestCode: Int = -1, action: () -> Unit) {
        val session = session(activity)
        val token = session.state.leave(session.source()) ?: return
        if (requestCode >= 0) session.results[requestCode] = token
        session.refresh()
        try { action() } catch (failure: Throwable) {
            session.state.cancelDeparture(token)
            if (session.results[requestCode] === token) session.results.remove(requestCode)
            session.refresh()
            throw failure
        }
    }

    fun activityResult(activity: Activity, requestCode: Int) {
        val session = sessions[activity] ?: return
        session.results.remove(requestCode)?.let(session.state::cancelDeparture)
        session.refresh()
    }

    /** 权限弹窗不保证让 Activity 暂停；必须由其结果回调释放。 */
    fun permission(activity: Activity): (() -> Unit)? {
        val session = session(activity)
        val token = session.state.leave(session.source()) ?: return null
        session.refresh()
        return { session.state.cancelDeparture(token); session.refresh() }
    }

    private fun <T> input(session: Session, token: Any, action: () -> T): T {
        val origin = Origin(token)
        session.origin = origin
        return try { action() } finally {
            // View 在 ACTION_UP 中 post 的 performClick 排在此清理之前，仍携带旧来源。
            handler.post { if (session.origin === origin) session.origin = null }
        }
    }

    /** 每个窗口单独跟踪手势；被中止的手势不能从中途重新送入下层 View。 */
    class InputDispatcher internal constructor(private val session: Session, private val token: Any) {
        private var rejectedGesture = false
        private var deliveringGesture = false
        fun key(event: KeyEvent, deliver: (KeyEvent) -> Boolean): Boolean {
            if (event.keyCode == KeyEvent.KEYCODE_BACK) return input(session, token) { deliver(event) }
            if (!session.state.canInteract(token)) return true
            return input(session, token) { deliver(event) }
        }
        fun dispatch(event: MotionEvent, deliver: (MotionEvent) -> Boolean): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                rejectedGesture = !session.state.canInteract(token)
            }
            if (rejectedGesture || !session.state.canInteract(token)) {
                rejectedGesture = true
                if (deliveringGesture) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    try { deliver(cancel) } finally { cancel.recycle() }
                    deliveringGesture = false
                }
                return true
            }
            deliveringGesture = event.actionMasked != MotionEvent.ACTION_UP &&
                event.actionMasked != MotionEvent.ACTION_CANCEL
            return input(session, token) { deliver(event) }
        }
    }

    private class InputCallback(
        callback: Window.Callback, private val session: Session, val token: Any
    ) : WindowCallbackWrapper(callback) {
        private val dispatcher = InputDispatcher(session, token)
        override fun dispatchTouchEvent(event: MotionEvent): Boolean =
            dispatcher.dispatch(event) { super.dispatchTouchEvent(it) }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            return dispatcher.key(event) { super.dispatchKeyEvent(it) }
        }
        override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
            if (!session.state.canInteract(token)) return true
            return input(session, token) { super.dispatchGenericMotionEvent(event) }
        }
    }

    private fun installWindow(window: Window, session: Session, token: Any) {
        val callback = window.callback ?: return
        if (callback is InputCallback && callback.token === token) return
        window.callback = InputCallback(callback, session, token)
        val root = window.decorView
        // 系统 DecorView 可能早于 Activity 的主题覆盖创建，需要单独设置一次。
        (root as? ViewGroup)?.isMotionEventSplittingEnabled = false
        session.roots.putIfAbsent(token, root to root.importantForAccessibility)
        session.refresh()
    }
}
