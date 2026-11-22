package cn.local.actionrelay.updates

import org.json.JSONObject

/** Release versions are three numeric parts; compare numbers, never display strings. */
data class AppVersion(val major: Int, val minor: Int, val patch: Int): Comparable<AppVersion> {
    override fun compareTo(other: AppVersion): Int = compareValuesBy(this, other, {it.major}, {it.minor}, {it.patch})
    override fun toString() = "$major.$minor.$patch"
    companion object {
        @JvmStatic fun parse(text: String): AppVersion {
            val match = Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)").matchEntire(text)
                ?: throw IllegalArgumentException("无法识别版本号")
            val parts = match.groupValues.drop(1).map { it.toIntOrNull() ?: throw IllegalArgumentException("版本号超出范围") }
            return AppVersion(parts[0], parts[1], parts[2])
        }
    }
}

data class ReleaseUpdate(
    @JvmField val version: String,
    @JvmField val isNewer: Boolean,
    @JvmField val notes: String,
    @JvmField val publishedDate: String,
    @JvmField val releaseUrl: String,
    @JvmField val apkUrl: String?,
    @JvmField val watchUrl: String?,
) {
    companion object {
        const val REPOSITORY = "https://github.com/GuYanjiao/action-relay"
        const val API_URL = "https://api.github.com/repos/GuYanjiao/action-relay/releases/latest"
        const val MAX_RESPONSE_BYTES = 512 * 1024

        @JvmStatic fun parse(currentVersion: String, text: String): ReleaseUpdate {
            val current = AppVersion.parse(currentVersion)
            val json = JSONObject(text)
            require(!json.getBoolean("draft") && !json.getBoolean("prerelease")) { "暂未找到正式发布版本" }
            val remote = AppVersion.parse(json.getString("tag_name"))
            val version = remote.toString()
            val prefix = "$REPOSITORY/releases/download/v$version/"
            val apkName = "ActionRelay-$version.apk"
            val watchName = "ActionRelay-Watch-$version.rpk"
            var apk: String? = null
            var watch: String? = null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name")
                val url = asset.optString("browser_download_url")
                // Only link this repository's matching pair, never an unrelated ABI or release.
                if (name == apkName && url == prefix + apkName) apk = url
                if (name == watchName && url == prefix + watchName) watch = url
            }
            return ReleaseUpdate(version, remote > current, if(json.isNull("body")) "" else json.optString("body").take(24000),
                json.optString("published_at").take(10), "$REPOSITORY/releases/tag/v$version", apk, watch)
        }
    }
}
