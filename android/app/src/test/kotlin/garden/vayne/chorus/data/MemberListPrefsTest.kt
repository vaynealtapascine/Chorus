package garden.vayne.chorus.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MemberListPrefsTest {
    private val fixture = JSONObject(java.io.File(System.getProperty("chorus.fixtures"), "ui_member_list.json").readText())

    private fun view(o: JSONObject) = MembersView(o.getString("pills"), o.optString("role_field").ifEmpty { null },
        o.getBoolean("bio"), o.getBoolean("banners"), o.getBoolean("flat"))

    @Test fun readsTheSharedFixtureLikeWeb() {
        val s = MemberListPrefs.parse(JSONObject().put("rows", JSONObject().put("pref", fixture.getJSONObject("prefs"))), fixture.getString("account"))
        assertEquals(view(fixture.getJSONObject("view")), s.view)
        val folders = fixture.getJSONArray("folders")
        assertEquals(List(folders.length()) { folders.getJSONObject(it).let { f -> MemberFolder(f.getString("id"), f.getString("name")) } }, s.folders)
        val of = fixture.getJSONObject("folder_of")
        assertEquals(of.keys().asSequence().associateWith { of.getString(it) }, s.folderOf)
        assertEquals(view(fixture.getJSONObject("default_view")), MemberListPrefs.parse(JSONObject(), "acc").view)
        assertEquals(MembersView(), MemberListPrefs.parse(JSONObject(), "acc").view)
    }

    @Test fun readsRolesAndBioLinesLikeWeb() {
        val roles = fixture.getJSONArray("roles")
        for (i in 0 until roles.length()) {
            val r = roles.getJSONObject(i)
            val want = r.getJSONArray("roles")
            assertEquals(List(want.length()) { want.getString(it) }, MemberListPrefs.rolesOf(r.opt("value")))
        }
        val bio = fixture.getJSONArray("bio")
        for (i in 0 until bio.length()) {
            val b = bio.getJSONObject(i)
            assertEquals(if (b.isNull("line")) null else b.getString("line"), MemberListPrefs.bioLine(if (b.isNull("text")) null else b.getString("text")))
        }
    }

    @Test fun picksTheRolesField() {
        fun def(id: String, name: String, type: String) = RoleFieldDef(id, name, type, emptyList(), JSONObject())
        val defs = listOf(def("n", "Notes", "text"), def("m", "Kinds", "multi_select"), def("r", " roles ", "select"))
        assertEquals("n", MemberListSettings(MembersView(roleField = "n"), roleFields = defs).roleField?.id)
        assertEquals("r", MemberListSettings(MembersView(roleField = "gone"), roleFields = defs).roleField?.id)
        assertEquals("m", MemberListSettings(roleFields = defs.take(2)).roleField?.id)
        assertNull(MemberListSettings(roleFields = defs.take(1)).roleField)
        assertFalse(MemberListSettings(roleFields = defs.take(1)).hasRolesField)
        assertEquals(listOf("Host"), MemberListPrefs.pillsFor(listOf("Host", "Protector"), "first"))
        assertEquals(emptyList<String>(), MemberListPrefs.pillsFor(listOf("Host"), "off"))
        assertEquals(MemberListPrefs.roleColor("Host"), MemberListPrefs.roleColor("host"))
    }

    @Test fun togglesChoicesPerFieldType() {
        fun def(type: String) = RoleFieldDef("f", "Roles", type, emptyList(), JSONObject())
        assertEquals("Host", MemberListPrefs.toggled(def("select"), emptyList(), "Host"))
        assertEquals("", MemberListPrefs.toggled(def("select"), listOf("Host"), "Host"))
        assertEquals(JSONArray(listOf("Host", "Little")).toString(), MemberListPrefs.toggled(def("multi_select"), listOf("Host"), "Little").toString())
        assertEquals("Host", MemberListPrefs.toggled(def("text"), listOf("Host", "Little"), "Little"))
    }

    @Test fun sectionsAndFolderEdits() {
        fun m(id: String) = Member(id, id, null, null, "#123456", emptyList(), null, false)
        val folders = listOf(MemberFolder("f2", "Two"), MemberFolder("f1", "One"), MemberFolder("f3", "Empty"))
        val s = MemberListPrefs.sections(listOf(m("c"), m("a"), m("b"), m("d")), folders, mapOf("a" to "f1", "c" to "f1", "d" to "f2"))
        assertEquals(listOf("f2" to listOf("d"), "f1" to listOf("a", "c"), "f3" to emptyList(), null to listOf("b")),
            s.map { it.folder?.id to it.members.map(Member::id) })
        var f = MemberListPrefs.addFolder(emptyList(), "x", "  Littles ")
        f = MemberListPrefs.addFolder(f, "y", "")
        assertEquals(listOf(MemberFolder("x", "Littles"), MemberFolder("y", "Folder")), f)
        assertEquals("Folder", MemberListPrefs.renameFolder(f, "y", " ")[1].name)
        assertEquals(listOf("y", "x"), MemberListPrefs.moveFolder(f, "y", -1).map { it.id })
        assertSame(f, MemberListPrefs.moveFolder(f, "x", -1))
        assertEquals(listOf(MemberFolder("y", "Folder")), MemberListPrefs.removeFolder(f, "x"))
    }
}
