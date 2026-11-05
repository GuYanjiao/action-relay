package cn.local.actionrelay
import java.util.ArrayDeque

class WatchOutbox {
    class Item(@JvmField val json: String, @JvmField val id: String, @JvmField val result: Boolean, @JvmField val terminal: Boolean, @JvmField val queuedAt: Long)
    private val results = ArrayDeque<Item>()
    private var status: Item? = null
    private var inflight: Item? = null
    fun offer(json: String, id: String, result: Boolean, terminal: Boolean, now: Long) {
        val item = Item(json, id, result, terminal, now)
        if (!result) { status = item; return }
        val iterator = results.iterator()
        while (iterator.hasNext()) {
            val old = iterator.next()
            if (old.id == id) { if (old.terminal && !terminal) return; iterator.remove() }
        }
        check(results.size < 32) { "手表结果队列繁忙" }
        results.addLast(item)
        if (terminal) status = null
    }
    fun begin(): Item? {
        if (inflight != null) return null
        val next = results.pollFirst() ?: status.also { status = null }
        inflight = next; return next
    }
    fun finish(item: Item?): Boolean { if (item == null || item !== inflight) return false; inflight = null; return true }
    fun isInflight(item: Item?) = item != null && item === inflight
    fun clear() { results.clear(); status = null; inflight = null }
}
