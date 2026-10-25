package garden.vayne.chorus.core

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import uniffi.chorus_ffi.defaultSpeakerForFront
import uniffi.chorus_ffi.foldFrontSystem
import uniffi.chorus_ffi.frontEffectiveHistory
import uniffi.chorus_ffi.namedFrontDaily
import uniffi.chorus_ffi.resolveFrontSystem

/** Same checked-in JSON vectors as Rust/wasm, through generated Kotlin and the real host DLL. */
class FrontConformanceTest {
    private val root = File(requireNotNull(System.getProperty("chorus.fixtures")))

    @Test
    fun namedFrontFixturesMatchTheSharedContract() {
        val files = listOf("front_system", "front_history", "front_effective").flatMap { area ->
            File(root, area).listFiles().orEmpty().filter { it.extension == "json" }.sortedBy { it.name }
        }
        assertTrue("No front conformance fixtures found at $root", files.isNotEmpty())
        for (file in files) {
            val case = JSONObject(file.readText())
            val args = case.getJSONArray("args")
            fun arg(i: Int): String = args.get(i).let { if (it is String) it else it.toString() }
            val result = when (val function = case.getString("fn")) {
                "resolve_front_system" -> resolveFrontSystem(arg(0))
                "fold_front_system" -> foldFrontSystem(arg(0), arg(1))
                "front_effective_history" -> frontEffectiveHistory(arg(0), arg(1), arg(2))
                "named_front_daily" -> namedFrontDaily(arg(0), args.getLong(1), arg(2))
                "default_speaker_for_front" -> defaultSpeakerForFront(arg(0), arg(1), arg(2), arg(3))
                else -> error("Unsupported front fixture: $function")
            }
            assertEquals(file.name, normalized(case.get("expect")), normalized(JSONTokener(result).nextValue()))
        }
    }

    @Test
    fun historyCorrectionsKeepFollowing() {
        val fixture = JSONObject(File(root, "front_history/resume_keeps_saved_history.json").readText())
        val args = fixture.getJSONArray("args")
        val account = args.getString(0)
        val ops = JSONArray(args.getJSONArray(1).toString())
        val changed = (0 until ops.length()).map { ops.getJSONObject(it) }.first { it.getString("kind") == "front.change" }
        val front = changed.getString("entity_id")
        ops.put(JSONObject().put("id", "0192f8c2-0000-7000-8000-000000000006")
            .put("kind", "front.change").put("entity_id", front).put("scope", "account:$account")
            .put("hlc", "000000001770-0000-00000001").put("device_at", 6000)
            .put("payload", JSONObject().put("action", "amend").put("target_op_id", changed.getString("id"))
                .put("note", "A history correction")))
        val corrected = JSONObject(foldFrontSystem(account, ops.toString()))
        assertEquals("following", corrected.getJSONObject("resolved").getJSONObject(front).getString("state"))
        assertEquals("A history correction", corrected.getJSONObject("histories").getJSONObject(front)
            .getJSONArray("switches").getJSONObject(0).getString("note"))
        for ((index, action) in listOf("retract", "unretract").withIndex()) {
            val time = 7000 + index * 1000
            ops.put(JSONObject().put("id", "0192f8c2-0000-7000-8000-${(7 + index).toString().padStart(12, '0')}")
                .put("kind", "front.change").put("entity_id", front).put("scope", "account:$account")
                .put("hlc", "${time.toString(16).padStart(12, '0')}-0000-00000001").put("device_at", time)
                .put("payload", JSONObject().put("action", action).put("target_op_id", changed.getString("id"))))
            val result = JSONObject(foldFrontSystem(account, ops.toString()))
            assertEquals("following", result.getJSONObject("resolved").getJSONObject(front).getString("state"))
            assertEquals(normalized(corrected.getJSONObject("resolved").getJSONObject(front).get("effective")),
                normalized(result.getJSONObject("resolved").getJSONObject(front).get("effective")))
            assertEquals(action == "retract", result.getJSONObject("histories").getJSONObject(front)
                .getJSONArray("switches").getJSONObject(0).getBoolean("retracted"))
        }
    }

    private fun normalized(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> value.keys().asSequence().sorted().associateWith { normalized(value.get(it)) }
        is JSONArray -> (0 until value.length()).map { normalized(value.get(it)) }
        else -> value
    }
}
