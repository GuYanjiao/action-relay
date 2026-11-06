package cn.local.actionrelay

class ReconnectPolicy {
    companion object { const val MAX_ATTEMPTS = 5 }
    class Ticket internal constructor(internal val generation: Int, @JvmField val delayMs: Long)
    private var generation = 0
    private var attempts = 0
    private var pending: Ticket? = null
    private var active = true
    fun reset() { cancel(); active = true; attempts = 0 }
    fun cancel() { generation++; pending = null; active = false }
    fun schedule(): Ticket? {
        if (!active || pending != null || attempts >= MAX_ATTEMPTS) return null
        return Ticket(generation, if (attempts == 0) 0 else minOf(30000L, 3000L shl (attempts - 1))).also { pending = it }
    }
    fun claim(ticket: Ticket?): Boolean {
        if (!active || ticket == null || ticket !== pending || ticket.generation != generation) return false
        pending = null; attempts++; return true
    }
    fun exhausted() = active && attempts >= MAX_ATTEMPTS && pending == null
}
