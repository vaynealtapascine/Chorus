package garden.vayne.chorus.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedRulesTest {
    @Test fun rowsRoundTripThroughTheQueryString() {
        val q = "(from:@Foxglove or from:@Violet) (tag:calm or tag:garden) kind:entry since:30d reply:false"
        val rules = FeedRules.parse(q)!!
        assertEquals(listOf("Foxglove", "Violet"), rules.values("from"))
        assertEquals(listOf("calm", "garden"), rules.values("tag"))
        assertEquals(listOf("entry"), rules.values("kind"))
        assertFalse(rules.replies)
        assertEquals(q, rules.query())
    }

    @Test fun namesWithSpacesAreQuoted() {
        val rules = FeedRules().with("from", listOf("Mr Fox"))
        assertEquals("from:\"Mr Fox\"", rules.query())
        assertEquals(listOf("Mr Fox"), FeedRules.parse(rules.query())!!.values("from"))
        assertEquals("", FeedRules().query())
        assertEquals(FeedRules(), FeedRules.parse("  "))
    }

    @Test fun aQuerySaidWithMoreThanRowsIsLeftAlone() {
        assertNull(FeedRules.parse("from:@kai or from:@juniper"))
        assertNull(FeedRules.parse("-tag:vent"))
        assertNull(FeedRules.parse("has:image"))
        assertNull(FeedRules.parse("hello"))
        assertNull(FeedRules.parse("(from:@a or tag:b)"))
        assertNull(FeedRules.parse("from:@a from:@b"))
        assertNull(FeedRules.parse("reply:true"))
        assertNull(FeedRules.parse("kind:message"))
        assertNull(FeedRules.parse("tag:x (from:@a"))
    }

    @Test fun pinnedTabsKeepOrderAndDropFeedsThatAreGone() {
        assertEquals(AccountPrefs.DEFAULT_TABS, AccountPrefs().tabs(emptySet()))
        val prefs = AccountPrefs(pinnedFeeds = listOf("entries", "f1", "gone", "system"))
        assertEquals(listOf("entries", "f1", "system"), prefs.tabs(setOf("f1")))
        assertEquals(AccountPrefs.DEFAULT_TABS, AccountPrefs(pinnedFeeds = listOf("gone")).tabs(emptySet()))
        assertEquals(listOf("system", "f2"), AccountPrefs.pinned(listOf("system"), "f2", true))
        assertEquals(listOf("system"), AccountPrefs.pinned(listOf("system", "f2"), "f2", false))
    }

    @Test fun ageUnits() {
        assertEquals(30 * 86_400_000L, FeedRules.age("30d"))
        assertEquals(3_600_000L, FeedRules.age("1h"))
        assertNull(FeedRules.age("soon"))
    }

    @Test fun removingARowDropsItFromTheQuery() {
        val rules = FeedRules.parse("tag:calm kind:note")!!
        assertEquals("tag:calm", rules.without("kind").query())
    }
}
