package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileMediaTest {
    @Test fun postsKeepLinkedMediaInOrderAndIgnoreMissingOrDeletedRows() {
        val p = JSONObject("""{"rows":{
            "attachment":{
                "photo":{"exists":true,"fields":{"blob_hash":"hash-photo","thumb_blob_hash":"hash-thumb","filename":"garden.jpg","mime":"image/jpeg","size":100,"alt_text":"Purple flowers","is_spoiler":true}},
                "file":{"exists":true,"fields":{"blob_hash":"hash-file","filename":"notes.pdf","mime":"application/pdf","size":200}},
                "absent":{"exists":false,"fields":{"blob_hash":"hash-absent"}}},
            "post":{
                "live":{"exists":true,"fields":{"kind":"entry","authors":["kai"],"text":"Garden","occurred_at":10,"attachments":["file","missing","photo","absent"]}},
                "deleted":{"exists":true,"fields":{"kind":"note","authors":["kai"],"text":"Old","occurred_at":11,"deleted_at":12,"attachments":["photo"]}}
            }}}""")
        val model = Model.parse(p, "acct")
        assertEquals(listOf("live"), model.posts.map { it.id })
        val media = model.posts.single().attachments
        assertEquals(listOf("file", "photo"), media.map { it.id })
        assertEquals("hash-thumb", media[1].thumbHash)
        assertEquals("Purple flowers", media[1].altText)
        assertTrue(media[1].spoiler)
    }
}
