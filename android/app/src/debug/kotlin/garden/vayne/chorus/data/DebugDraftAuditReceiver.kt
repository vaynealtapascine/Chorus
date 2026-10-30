package garden.vayne.chorus.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Read-only nonpublic audit, emulator + exact fictional account only. Never exports device/session
 * credentials. Use run-as to pull files/ui6-editor-audit.json; release has no receiver or export.
 */
class DebugDraftAuditReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "garden.vayne.chorus.DEBUG_DRAFT_AUDIT" ||
            !(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk_gphone"))) return
        val pending = goAsync()
        Thread {
            try {
                val store = Store(context.applicationContext)
                val device = store.get("device")?.let(DeviceRecord::fromJson) ?: return@Thread
                check(device.accountId == "01a0c450-6c00-7000-8000-000000010000") { "Fictional-account audit only" }
                val owner = "${device.base}|${device.accountId}"
                val prefix = "editor-draft-v1:${owner.length}:$owner:"
                val rows = store.editorDrafts().filter { it.k.startsWith(prefix) }
                val report = JSONObject().put("account", device.accountId).put("drafts", JSONArray(rows.map {
                    val entry = JSONObject().put("key", it.k).put("value", JSONObject(it.v))
                    if (it.k.contains(":profile:")) {
                        val draft = ProfileEditorDraft.decode(it.v)
                        val model = Chorus.get(context).model.value
                        entry.put("profile_rich", draft.bio.parse(ChatEdit.names(draft.bio.originalText, draft.bio.originalEntities, model)))
                    }
                    entry
                }))
                File(context.filesDir, "ui6-editor-audit.json").writeText(report.toString())
            } catch (e: Exception) {
                File(context.filesDir, "ui6-editor-audit.json").writeText(JSONObject().put("error", e.javaClass.simpleName).toString())
            } finally { pending.finish() }
        }.start()
    }
}
