package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPrefsTest {
    @Test fun pendingKeyWinsAndSingleKindEditPreservesOtherChoices() {
        val p = JSONObject("""{"rows":{"pref":{
            "acct||notify_chat":{"exists":true,"fields":{"value":{"mention":true,"dm":false,"reply":false}}},
            "||notify_chat":{"exists":true,"fields":{"value":{"mention":false,"dm":false,"reply":true}}},
            "||chat.cw_auto_expand":{"exists":true,"fields":{"value":true}},
            "acct||chat.segment_parsing":{"exists":true,"fields":{"value":false}}
        }}}""")
        val prefs = AccountPrefs.fromProjection(p, "acct")
        assertFalse(prefs.chatEnabled("mention"))
        assertFalse(prefs.chatEnabled("dm"))
        assertTrue(prefs.chatEnabled("reply"))
        assertFalse(prefs.chatEnabled("own_switch"))
        assertTrue(prefs.cwAutoExpand)
        assertFalse(prefs.segmentParsing)
        val updated = prefs.withChatKind("mention", true)
        assertTrue(updated.getBoolean("mention"))
        assertFalse(updated.getBoolean("dm"))
        assertTrue(updated.getBoolean("reply"))
        assertFalse(prefs.chatEnabled("mention"))
        val payload = AccountPrefs.payload("notify_chat", updated)
        assertEquals("", payload.getString("device"))
        assertEquals("notify_chat", payload.getString("key"))
    }

    @Test fun legacyCeilingIsOnlyUsedWithoutASeparatePref() {
        val p = JSONObject("""{"rows":{"account":{"acct":{"exists":true,"fields":{"settings":{"follow_ceiling":{"share_stats":true}}}}}}}""")
        assertTrue(AccountPrefs.fromProjection(p, "acct").followCeiling.getBoolean("share_stats"))
        p.getJSONObject("rows").put("pref", JSONObject("""{"||follow_ceiling":{"exists":true,"fields":{"value":{"share_history":true}}}}"""))
        val current = AccountPrefs.fromProjection(p, "acct").followCeiling
        assertTrue(current.getBoolean("share_history"))
        assertFalse(current.has("share_stats"))
    }
}
