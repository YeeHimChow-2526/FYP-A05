package com.hkparking.copilot.poi

import com.hkparking.copilot.net.MiniJson
import java.text.Normalizer
import java.util.Locale

// Step 3.1：本機字典樹（純 Kotlin，未接駁；接駁屬 Step 3.1b）。資料：assets/hk_poi_master.json（tools/poi/build_poi_master.py 產生）
data class Poi(
  val id: String,
  val type: Char,
  val zh: String,
  val en: String,
  val aliases: List<String>,
  val district: String,
  val lat: Double,
  val lng: Double,
)

sealed class TrieResult {
  data class Hit(val poi: Poi) : TrieResult()
  data class Ambiguous(val candidates: List<Poi>) : TrieResult()
  object Miss : TrieResult()
}

class LocalPoiTrie private constructor(
  private val root: Node,
  val size: Int,
  val attribution: String,
) {
  private class Node {
    val children = HashMap<Char, Node>(4)
    var pois: MutableList<Poi>? = null
  }

  fun lookup(query: String): TrieResult {
    val key = normalize(query)
    if (key.isEmpty()) return TrieResult.Miss
    // 完整 key 命中（任何長度）優先；否則取 query 最長而長度 >= 2 之完整 key 前綴
    var node = root
    var best: List<Poi>? = null
    for (i in key.indices) {
      node = node.children[key[i]] ?: break
      val depth = i + 1
      val here = node.pois
      if (here != null && (depth == key.length || depth >= MIN_PREFIX_KEY_LEN)) best = here
    }
    return resolve(best ?: return TrieResult.Miss)
  }

  private fun resolve(candidates: List<Poi>): TrieResult {
    val bestType = TYPE_PRIORITY.first { t -> candidates.any { it.type == t } }
    val group = candidates.filter { it.type == bestType }
    val byDistrict = group.groupBy { it.district }
    if (byDistrict.size == 1) return TrieResult.Hit(group.minByOrNull { it.id }!!)
    val picks = byDistrict.entries
      .sortedBy { it.key }
      .map { e -> e.value.minByOrNull { it.id }!! }
      .take(MAX_AMBIGUOUS)
    return TrieResult.Ambiguous(picks)
  }

  companion object {
    private const val MIN_PREFIX_KEY_LEN = 2
    private const val MAX_AMBIGUOUS = 3
    private val TYPE_PRIORITY = charArrayOf('A', 'M', 'C', 'D')

    // 最長優先；只剝一次
    private val SUFFIXES = listOf(
      "停車場", "停車塲", "商場", "購物中心", "carpark", "carparks", "parking",
      "shoppingmall", "shoppingcentre", "mall",
    ).sortedByDescending { it.length }

    fun normalize(s: String): String {
      val nfkc = Normalizer.normalize(s, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
      val sb = StringBuilder(nfkc.length)
      var i = 0
      while (i < nfkc.length) {
        val cp = nfkc.codePointAt(i)
        if (!isPunctOrSeparator(cp)) sb.appendCodePoint(cp)
        i += Character.charCount(cp)
      }
      val base = sb.toString()
      for (suf in SUFFIXES) {
        if (base.endsWith(suf)) {
          val stripped = base.substring(0, base.length - suf.length)
          return if (stripped.isEmpty()) base else stripped
        }
      }
      return base
    }

    private fun isPunctOrSeparator(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
      Character.CONNECTOR_PUNCTUATION,
      Character.DASH_PUNCTUATION,
      Character.START_PUNCTUATION,
      Character.END_PUNCTUATION,
      Character.INITIAL_QUOTE_PUNCTUATION,
      Character.FINAL_QUOTE_PUNCTUATION,
      Character.OTHER_PUNCTUATION,
      Character.SPACE_SEPARATOR,
      Character.LINE_SEPARATOR,
      Character.PARAGRAPH_SEPARATOR -> true
      else -> false
    }

    fun fromJson(text: String): LocalPoiTrie {
      val doc = MiniJson.parseObjectOrNull(text) ?: throw IllegalArgumentException("hk_poi_master.json: invalid JSON")
      val attribution = doc["attribution"] as? String ?: ""
      val rawPois = doc["pois"] as? List<*> ?: throw IllegalArgumentException("hk_poi_master.json: missing pois")
      val root = Node()
      var count = 0
      for (raw in rawPois) {
        val m = raw as? Map<*, *> ?: throw IllegalArgumentException("hk_poi_master.json: poi not an object")
        val poi = Poi(
          id = m["id"] as? String ?: throw IllegalArgumentException("poi without id"),
          type = (m["t"] as? String)?.firstOrNull() ?: throw IllegalArgumentException("poi without t"),
          zh = m["zh"] as? String ?: "",
          en = m["en"] as? String ?: "",
          aliases = (m["a"] as? List<*>)?.filterIsInstance<String>() ?: emptyList(),
          district = m["d"] as? String ?: "",
          lat = (m["la"] as? Number)?.toDouble() ?: throw IllegalArgumentException("poi without la"),
          lng = (m["lo"] as? Number)?.toDouble() ?: throw IllegalArgumentException("poi without lo"),
        )
        count += 1
        val keys = LinkedHashSet<String>()
        for (rawKey in listOf(poi.zh, poi.en) + poi.aliases) {
          if (rawKey.isEmpty()) continue
          val k = normalize(rawKey)
          if (k.isNotEmpty()) keys += k
        }
        for (k in keys) insert(root, k, poi)
      }
      return LocalPoiTrie(root, count, attribution)
    }

    private fun insert(root: Node, key: String, poi: Poi) {
      var node = root
      for (ch in key) node = node.children.getOrPut(ch) { Node() }
      val list = node.pois ?: ArrayList<Poi>(1).also { node.pois = it }
      if (list.none { it.id == poi.id }) list += poi
    }
  }
}
