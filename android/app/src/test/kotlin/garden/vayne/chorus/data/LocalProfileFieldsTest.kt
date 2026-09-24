package garden.vayne.chorus.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class LocalProfileFieldsTest {
    @Test fun projectionShowsLiveTypedValuesInDefinitionOrder() {
        val p = JSONObject("""{"rows":{"field_def":{
            "notes":{"exists":true,"fields":{"name":"Notes","type":"long_text","sort_key":"b"}},
            "flags":{"exists":true,"fields":{"name":"Available","type":"boolean","sort_key":"a"}},
            "tags":{"exists":true,"fields":{"name":"Tags","type":"multi_select","sort_key":"c"}},
            "gone":{"exists":true,"fields":{"name":"Old","deleted_at":1}},
            "removed":{"exists":false,"fields":{"name":"Removed"}}},
            "field_value":{
            "kai|notes":{"exists":true,"fields":{"value":"Hello"}},
            "kai|flags":{"exists":true,"fields":{"value":false}},
            "kai|tags":{"exists":true,"fields":{"value":["one","two"]}},
            "kai|gone":{"exists":true,"fields":{"value":"hidden"}},
            "kai|removed":{"exists":true,"fields":{"value":"hidden"}},
            "rin|notes":{"exists":true,"fields":{"value":"Other member"}},
            "rin|flags":{"exists":true,"fields":{"value":null}},
            "rin|tags":{"exists":false,"fields":{"value":["old"]}}
        }}}""")
        val model = Model.parse(p, "acct")
        assertEquals(listOf(ProfileField("Available", "No"), ProfileField("Notes", "Hello"),
            ProfileField("Tags", "one, two")), model.profileFields["kai"])
        assertEquals(listOf(ProfileField("Notes", "Other member")), model.profileFields["rin"])
    }
}
