package garden.vayne.chorus.data

/** A bounded, guarded history window containing a search target, never the model's newest 100. */
object SearchJump {
    data class Rows(val messages:List<ChatMessage>,val ancestors:Map<String,ChatMessage>)
    suspend fun load(model:Model, account:String, channel:String, target:String,
        ancestors:Map<String,ChatMessage>, local:suspend (SearchVisibility)->List<ChatMessage>,
        remote:suspend ()->List<ChatMessage>):Rows {
        val view=SearchVisibility(model,account,channel,ancestors)
        val localRows=local(view)
        val rows=(if(localRows.any {it.id==target}) localRows else remote())
            .filter {it.channelId==channel && view.message(it)}
            .distinctBy {it.id}
            .sortedWith(compareBy<ChatMessage>{it.occurredAt}.thenBy {it.id})
        return Rows(if(rows.any {it.id==target}) rows.take(100) else emptyList(),ancestors)
    }
}
