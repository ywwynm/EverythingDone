package com.ywwynm.everythingdone.views.recording.fablesol

/** 直接图层的真实请求状态：只发布仍被请求的帧，提交前不丢弃屏上的旧图层。 */
internal class FableSolLayerTransition {
    data class Spec(val output: FableSolScreenOutput, val width: Int, val height: Int)
    data class Ticket(val id: Long, val spec: Spec)
    private var sequence = 0L
    var requested: Spec? = null
        private set
    var preparing: Ticket? = null
        private set
    var shown: Ticket? = null
        private set

    fun request(spec: Spec) { requested = spec }
    fun begin(force: Boolean = false): Ticket? {
        val spec = requested ?: return null
        if (preparing != null || (!force && shown?.spec == spec)) return null
        return Ticket(++sequence, spec).also { preparing = it }
    }
    fun isCurrent(ticket: Ticket): Boolean = preparing == ticket
    fun canPublish(ticket: Ticket): Boolean = isCurrent(ticket) && requested == ticket.spec
    fun discard(ticket: Ticket) { if (isCurrent(ticket)) preparing = null }
    /** 已排入合成的事务即使被后一个请求替代，也须先登记实际显示的图层，再执行最新请求。 */
    fun committed(ticket: Ticket): Boolean {
        if (!isCurrent(ticket)) return false
        shown = ticket
        preparing = null
        return true
    }
    fun reset() { sequence++; requested = null; preparing = null; shown = null }
}
