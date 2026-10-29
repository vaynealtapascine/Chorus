// Review reproduction only: copy into app/src/test as documented in ui6-batch2-review.md.
// Two tests intentionally fail on 193d91f; keep outside the passing test suite until corrected.
package garden.vayne.chorus.data
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONArray
import kotlin.random.Random
class ReviewBatch2AdversarialTest {
 private fun member(id:String,name:String=id,key:String?=null)=Member(id,name,null,null,"#123456",emptyList(),null,false,sortKey=key)
 @Test fun distinctMentionTargetsSurviveNearbyEdit() {
  val old=member("m").copy(description="@Kai @Kai",descriptionEntities="""[{"type":"mention","offset":0,"length":4,"target_type":"member","target_id":"alpha"},{"type":"mention","offset":5,"length":4,"target_type":"member","target_id":"beta"}]""")
  val markup=MemberCustomization.markup(old)
  val patch=MemberCustomization.patch(old,old.name,"","","",old.color,markup+"!",markup,null,null,"",names=ChatEdit.names(old.description!!,old.descriptionEntities,Model.Empty))
  val entities=patch.getJSONArray("description_entities")
  println("REVIEW mentions="+entities)
  assertEquals("alpha",entities.getJSONObject(0).getString("target_id"))
  assertEquals("beta",entities.getJSONObject(1).getString("target_id"))
 }
 @Test fun unicodeOrderMatchesActualWebRuntime() {
  val members=listOf(member("z","\u1c89"),member("a","\u1c8a"))
  println("REVIEW unicode keys="+members.map { MemberOrdering.key(it) })
  assertEquals(listOf("a","z"), MemberOrdering.ordered(members).map {it.id})
 }
 @Test fun pathologicalLegacyPositionsRemainStrictAndSparse() {
  val keys=listOf(null,"","legacy","~ui6:","~ui6: ","~ui6:a","~ui6:a!","~ui6:a  !","~ui6:~","~ui6:~~","~ui6:\u00e9","~ui6:\n","duplicate","duplicate")
  var rows=keys.mapIndexed { i,key-> member("id"+i.toString().padStart(2,'0'),"Name$i",key) }
  val random=Random(6509)
  repeat(600) {
   val current=MemberOrdering.ordered(rows)
   val expected=current.map {it.id}.toMutableList()
   val id=expected.removeAt(random.nextInt(expected.size))
   expected.add(random.nextInt(expected.size+1),id)
   val patches=MemberOrdering.patches(rows,"mine",expected,setOf(id))
   assertTrue(patches.size<=1)
   rows=rows.map {m-> patches.find {it.first==m.id}?.let {m.copy(sortKey=it.second)}?:m}
   assertEquals(expected,MemberOrdering.ordered(rows).map {it.id})
   val sorted=MemberOrdering.ordered(rows).map {MemberOrdering.key(it)}
   assertTrue(sorted.zipWithNext().all { (a,b)->a<b })
  }
 }
}
