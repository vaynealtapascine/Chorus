package garden.vayne.chorus.data

import kotlinx.coroutines.CancellationException

/** Ephemeral guarded dependencies, bound by the caller to a model/session generation. */
object SearchAncestors {
    suspend fun load(model: Model, wanted: Iterable<String> = model.searchMessages.mapNotNull {it.channelId}.distinct(), fetch: suspend (String) -> ChatMessage?): Map<String,ChatMessage> {
        val local=model.searchMessages.associateBy {it.id}
        val channels=model.channels.associateBy {it.id}
        val found=mutableMapOf<String,ChatMessage>()
        val attempted=HashSet<String>()
        suspend fun visit(id:String, seen:MutableSet<String>) {
            val c=channels[id] ?: return
            if(c.invalidMembership || c.kind!="thread" || seen.size>256 || !seen.add(id)) return
            val parentId=c.parentMessageId ?: return
            if(parentId in model.deletedMessageIds) return
            val own=local[parentId]
            if(own!=null) {own.channelId?.let {visit(it,seen)};return}
            if(parentId !in found && attempted.add(parentId)) {
                val parent=try {fetch(parentId)} catch(e:CancellationException) {throw e} catch(_:Exception) {null}
                if(parent?.id==parentId) found[parentId]=parent
            }
            found[parentId]?.let {visit(it.channelId,seen)}
        }
        for(id in wanted) visit(id,HashSet())
        return found
    }
    suspend fun load(model:Model,dev:DeviceRecord,wanted:Iterable<String>):Map<String,ChatMessage> = load(model,wanted) { id ->
        SearchVisibility.parseCard(Api.call("GET",dev.base,"/messages/$id",null,dev.session))
    }
}
