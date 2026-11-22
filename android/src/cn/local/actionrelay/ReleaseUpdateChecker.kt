package cn.local.actionrelay

import android.os.Handler
import android.os.Looper
import cn.local.actionrelay.updates.ReleaseUpdate
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** User-initiated HTTP only. This checker never attaches to the relay service or schedules polls. */
class ReleaseUpdateChecker(private val version: String, private val onState: (State) -> Unit) {
    data class State(val checking: Boolean = false, val release: ReleaseUpdate? = null, val error: String? = null)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newCachedThreadPool()
    @Volatile private var connection: HttpURLConnection? = null
    @Volatile private var closed = false
    private var checking = false

    fun check() {
        if (closed || checking) return
        checking = true
        onState(State(checking = true))
        worker.execute {
            val state = try { State(release = fetch()) }
            catch (error: Exception) { State(error = message(error)) }
            main.post { if (!closed) { checking = false; onState(state) } }
        }
    }

    private fun fetch(): ReleaseUpdate {
        val current = URL(ReleaseUpdate.API_URL).openConnection() as HttpURLConnection
        connection = current
        try {
            if (closed) throw IOException("检查已取消")
            current.connectTimeout = 8000
            current.readTimeout = 10000
            current.useCaches = false
            current.setRequestProperty("Accept", "application/vnd.github+json")
            current.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            current.setRequestProperty("User-Agent", "ActionRelay/$version")
            when (val status = current.responseCode) {
                200 -> Unit
                403, 429 -> throw IOException("GitHub 暂时限制请求，请稍后重试，或打开发布页面")
                404 -> throw IOException("暂未找到正式发布版本")
                else -> throw IOException("更新服务返回 HTTP $status，请稍后重试")
            }
            require(current.contentLengthLong <= ReleaseUpdate.MAX_RESPONSE_BYTES) { "更新信息过大" }
            val output = ByteArrayOutputStream()
            current.inputStream.use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    if (closed || Thread.currentThread().isInterrupted) throw IOException("检查已取消")
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= ReleaseUpdate.MAX_RESPONSE_BYTES) { "更新信息过大" }
                    output.write(buffer, 0, count)
                }
            }
            return ReleaseUpdate.parse(version, output.toString(Charsets.UTF_8.name()))
        } finally { current.disconnect(); connection = null }
    }

    private fun message(error: Exception): String = when (error) {
        is java.net.SocketTimeoutException -> "连接 GitHub 超时，请检查网络后重试"
        is java.net.UnknownHostException -> "无法连接 GitHub，请检查网络后重试"
        is IOException -> error.message ?: "无法检查更新，请检查网络后重试"
        else -> "无法读取发布信息，可打开 GitHub 发布页面查看"
    }

    fun close() {
        closed = true
        connection?.disconnect()
        worker.shutdownNow()
    }
}
