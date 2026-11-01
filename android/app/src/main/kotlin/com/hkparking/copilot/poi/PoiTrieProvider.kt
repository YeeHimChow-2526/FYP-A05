// FROZEN Step2 — NOT SoT; behavior mirror for FBS compile only; do not edit; delete after Step3+ FBS removal
package com.hkparking.copilot.poi

fun interface PoiTrieProvider {
  fun get(): LocalPoiTrie?
}

@Deprecated("SoT is Dart lib/core/poi/poi_trie_provider.dart")
class AssetPoiTrieProvider(private val open: () -> String) : PoiTrieProvider {
  @Volatile private var cached: LocalPoiTrie? = null
  @Volatile private var failed = false
  @Volatile private var loggedFailure = false

  fun prewarm() {
    val t = Thread({ get() }, "poi-trie-prewarm")
    t.isDaemon = true
    t.start()
  }

  @Synchronized
  override fun get(): LocalPoiTrie? {
    if (failed) return null
    cached?.let { return it }
    return try {
      val trie = LocalPoiTrie.fromJson(open())
      cached = trie
      trie
    } catch (t: Throwable) {
      failed = true
      if (!loggedFailure) {
        loggedFailure = true
        runCatching {
          android.util.Log.w("PoiTrieProvider", "Failed to load hk_poi_master.json: ${t.message}")
        }
      }
      null
    }
  }
}
