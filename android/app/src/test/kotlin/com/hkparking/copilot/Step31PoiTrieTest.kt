package com.hkparking.copilot

import com.hkparking.copilot.net.MiniJson
import com.hkparking.copilot.poi.LocalPoiTrie
import com.hkparking.copilot.poi.TrieResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Step31PoiTrieTest {
  // ---------- real asset ----------

  @Test
  fun step31_01_assetExistsUnderCapWithHeader() {
    val file = assetFile()
    assertTrue(file.isFile)
    assertTrue("bytes=${file.length()}", file.length() <= 153_600L)
    val doc = MiniJson.parseObjectOrNull(file.readText(Charsets.UTF_8))!!
    assertEquals(1L, doc["v"])
    assertTrue((doc["attribution"] as String).isNotEmpty())
    assertEquals(18, (doc["districts"] as List<*>).size)
    assertEquals(trie.attribution, doc["attribution"])
  }

  @Test
  fun step31_02_poiFieldsValid() {
    val doc = MiniJson.parseObjectOrNull(assetText())!!
    val districts = (doc["districts"] as List<*>).map { it as String }.toSet()
    val pois = (doc["pois"] as List<*>).map { it as Map<*, *> }
    val ids = pois.map { it["id"] as String }
    assertEquals(ids.size, ids.toSet().size)
    assertEquals(pois.size, trie.size)
    pois.forEach { p ->
      val id = p["id"] as String
      val la = (p["la"] as Number).toDouble()
      val lo = (p["lo"] as Number).toDouble()
      assertTrue("$id la=$la", la in 22.1..22.6)
      assertTrue("$id lo=$lo", lo in 113.8..114.6)
      assertTrue("$id d=${p["d"]}", p["d"] in districts)
      assertTrue(id, (p["zh"] as String).isNotEmpty())
      assertTrue("$id t=${p["t"]}", p["t"] in setOf("D", "A", "C", "M"))
    }
  }

  @Test
  fun step31_03_typeCounts() {
    val pois = (MiniJson.parseObjectOrNull(assetText())!!["pois"] as List<*>).map { it as Map<*, *> }
    val counts = pois.groupingBy { it["t"] as String }.eachCount()
    assertTrue("C=${counts["C"]}", (counts["C"] ?: 0) >= 500)
    assertEquals(18, counts["D"])
  }

  @Test
  fun step31_04_langhamHitsMallViaShangChangSuffix() {
    val hit = trie.lookup("朗豪坊") as TrieResult.Hit
    assertTrue(hit.poi.id, hit.poi.id.startsWith("ig:"))
    assertEquals('M', hit.poi.type)
    assertEquals("油尖旺", hit.poi.district)
  }

  @Test
  fun step31_05_mongKokAreaAndAliases() {
    val hit = trie.lookup("旺角") as TrieResult.Hit
    assertEquals('A', hit.poi.type)
    assertEquals("油尖旺", hit.poi.district)
    listOf("MK", "mk", "ＭＫ", "Mong Kok").forEach { q ->
      val other = trie.lookup(q)
      assertTrue(q, other is TrieResult.Hit)
      assertEquals(q, hit.poi.id, (other as TrieResult.Hit).poi.id)
    }
  }

  @Test
  fun step31_06_tinChingCarparkWithAndWithoutSuffix() {
    listOf("天晴邨第一期停車場", "天晴邨第一期").forEach { q ->
      val hit = trie.lookup(q) as TrieResult.Hit
      assertEquals(q, "td:tdc166p1", hit.poi.id)
    }
  }

  @Test
  fun step31_07_missCases() {
    listOf("火星停車場", "", "   ").forEach { q -> assertEquals("'$q'", TrieResult.Miss, trie.lookup(q)) }
  }

  @Test
  fun step31_08_performance() {
    val text = assetText()
    val t0 = System.nanoTime()
    val fresh = LocalPoiTrie.fromJson(text)
    val loadMs = (System.nanoTime() - t0) / 1_000_000.0
    assertTrue(fresh.size > 0)
    val queries = listOf("朗豪坊", "旺角", "MK", "Mong Kok", "天晴邨第一期停車場", "火星停車場", "尖沙咀", "apm", "旺角道口", "zzzz")
    var total = 0L
    var max = 0L
    repeat(1000) { i ->
      val s = System.nanoTime()
      fresh.lookup(queries[i % queries.size])
      val d = System.nanoTime() - s
      total += d
      if (d > max) max = d
    }
    val avgMs = total / 1000 / 1_000_000.0
    val maxMs = max / 1_000_000.0
    println("Step31 perf: fromJson=${"%.1f".format(loadMs)}ms pois=${fresh.size} lookup avg=${"%.4f".format(avgMs)}ms max=${"%.3f".format(maxMs)}ms")
    assertTrue("fromJson=${loadMs}ms", loadMs < 1500.0)
    assertTrue("avg=${avgMs}ms", avgMs < 1.0)
    assertTrue("max=${maxMs}ms", maxMs < NativeConfig.TRIE_LOOKUP_BUDGET_MS)
  }

  // ---------- inline JSON ----------

  @Test
  fun step31_09_sameAreaNameTwoDistrictsIsAmbiguous() {
    val t = inline(poi("pn:1", "A", "太平", "西貢"), poi("pn:2", "A", "太平", "北"))
    val r = t.lookup("太平") as TrieResult.Ambiguous
    assertEquals(2, r.candidates.size)
    assertEquals(listOf("北", "西貢"), r.candidates.map { it.district })
  }

  @Test
  fun step31_10_areaBeatsCarpark() {
    val t = inline(poi("td:x1", "C", "大角咀", "油尖旺"), poi("pn:9", "A", "大角咀", "油尖旺"))
    assertEquals("pn:9", (t.lookup("大角咀") as TrieResult.Hit).poi.id)
  }

  @Test
  fun step31_11_twoCarparksSameDistrictPickSmallerId() {
    val t = inline(poi("td:b", "C", "海富停車場", "中西"), poi("td:a", "C", "海富停車場", "中西"))
    assertEquals("td:a", (t.lookup("海富") as TrieResult.Hit).poi.id)
  }

  @Test
  fun step31_12_longestPrefixNeedsTwoChars() {
    val t = inline(poi("pn:1", "A", "旺角", "油尖旺"), poi("pn:2", "A", "旺", "油尖旺"))
    assertEquals("pn:1", (t.lookup("旺角道口") as TrieResult.Hit).poi.id)
    assertEquals(TrieResult.Miss, t.lookup("旺x"))
  }

  @Test
  fun step31_13_normalize() {
    assertEquals("朗豪坊", LocalPoiTrie.normalize("朗豪坊停車場"))
    assertEquals("langhamplace", LocalPoiTrie.normalize("Langham Place Carpark"))
    assertEquals("停車場", LocalPoiTrie.normalize("停車場"))
  }

  // ---------- helpers ----------

  private fun poi(id: String, t: String, zh: String, d: String) =
    """{"id":"$id","t":"$t","zh":"$zh","en":"","a":[],"d":"$d","la":22.3,"lo":114.1}"""

  private fun inline(vararg pois: String): LocalPoiTrie =
    LocalPoiTrie.fromJson("""{"v":1,"built":"2026-01-01","attribution":"x","districts":[],"pois":[${pois.joinToString(",")}]}""")

  private fun assetFile(): java.io.File {
    val candidates = listOf(
      java.io.File("src/main/assets/hk_poi_master.json"),
      java.io.File("app/src/main/assets/hk_poi_master.json"),
      java.io.File("android/app/src/main/assets/hk_poi_master.json"),
    )
    return candidates.first { it.isFile }
  }

  private fun assetText(): String = assetFile().readText(Charsets.UTF_8)

  private val trie: LocalPoiTrie by lazy { LocalPoiTrie.fromJson(assetText()) }
}
