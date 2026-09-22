package ru.ainur.feldshernotes

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import ru.ainur.feldshernotes.data.*
import java.time.LocalDate

enum class Screen { JOURNAL,DETAIL,EDITOR,SEARCH,MEMORY,MORE,PROFILE,REFERENCE,MKB }
data class NotesState(
    val ready: Boolean = false,val loadFailed: Boolean = false,val selectedDate: String = LocalDate.now().toString(),
    val calls: List<CallRecord> = emptyList(),val screen: Screen = Screen.JOURNAL,val openedId: String? = null,
    val detailOrigin: Screen = Screen.JOURNAL,val draft: CallRecord? = null,val draftIsNew: Boolean = true,
    val query: String = "",val saving: Boolean = false,val message: String? = null,val highlightId: String? = null
) {
    val dayCalls get() = calls.filter { it.date == selectedDate }
    val openedCall get() = calls.firstOrNull { it.id == openedId }
    val searchResults get() = searchCalls(calls,query)
}
class NotesViewModel(private val repository: NotesRepository,private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(NotesState())
    val state = mutable.asStateFlow()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    init {
        viewModelScope.launch {
            try {
                val date = saved.get<String>("selectedDate") ?: repository.selectedDate() ?: LocalDate.now().toString()
                val draft = repository.draft()
                val screen = saved.get<String>("screen")?.let { runCatching { Screen.valueOf(it) }.getOrNull() } ?: Screen.JOURNAL
                mutable.update { it.copy(ready = true,selectedDate = date,draft = draft?.first,draftIsNew = draft?.second ?: true,
                    screen = if (screen == Screen.EDITOR && draft == null) Screen.JOURNAL else screen,openedId = saved["openedId"],query = saved["query"] ?: "",
                    detailOrigin = if (saved.get<String>("detailOrigin") == "SEARCH") Screen.SEARCH else Screen.JOURNAL) }
            } catch (_: Exception) { mutable.update { it.copy(loadFailed = true,message = "Не удалось открыть журнал. Перезапустите приложение.") } }
            for (write in writes) try { write() } catch (_: Exception) {
                mutable.update { it.copy(saving = false,message = "Не удалось сохранить изменения. Текст остаётся на экране — попробуйте ещё раз.") }
            }
        }
        viewModelScope.launch {
            repository.calls.catch { mutable.update { it.copy(loadFailed = true,message = "Не удалось прочитать журнал.") } }
                .collect { calls -> mutable.update { it.copy(calls = calls) } }
        }
    }
    private fun enqueue(action: suspend () -> Unit) { writes.trySend(action) }
    private fun navigate(screen: Screen) { saved["screen"] = screen.name;mutable.update { it.copy(screen = screen) } }
    fun selectDate(date: String) {
        if (!state.value.ready) return
        LocalDate.parse(date);saved["selectedDate"] = date;mutable.update { it.copy(selectedDate = date,highlightId = null) }
        enqueue { repository.selectDate(date) }
    }
    fun shiftDate(days: Long) = selectDate(LocalDate.parse(state.value.selectedDate).plusDays(days).toString())
    fun openCall(id: String) {
        val origin = if (state.value.screen == Screen.SEARCH) Screen.SEARCH else Screen.JOURNAL
        saved["openedId"] = id;saved["detailOrigin"] = origin.name
        mutable.update { it.copy(openedId = id,detailOrigin = origin) };navigate(Screen.DETAIL)
    }
    fun newCall() {
        if (state.value.draft == null) {
            val record = CallRecord(date = state.value.selectedDate)
            mutable.update { it.copy(draft = record,draftIsNew = true) };enqueue { repository.saveDraft(record,true) }
        }
        navigate(Screen.EDITOR)
    }
    fun editCall() {
        val record = state.value.openedCall ?: return
        if (state.value.draft != null && state.value.draft?.id != record.id) {
            mutable.update { it.copy(message = "Сначала сохраните или удалите текущий черновик.") };return
        }
        val draft = state.value.draft ?: record
        mutable.update { it.copy(draft = draft,draftIsNew = false) };enqueue { repository.saveDraft(draft,false) };navigate(Screen.EDITOR)
    }
    fun resumeDraft() { if (state.value.draft != null) navigate(Screen.EDITOR) }
    fun changeDraft(transform: (CallRecord) -> CallRecord) {
        if (state.value.saving) return
        val draft = state.value.draft ?: return
        val updated = transform(draft);val isNew = state.value.draftIsNew
        mutable.update { it.copy(draft = updated) };enqueue { repository.saveDraft(updated,isNew) }
    }
    fun addBlock(kind: BlockKind) = changeDraft { it.copy(blocks = it.blocks + NoteBlock(kind = kind)) }
    fun updateBlock(id: String,key: String,value: String) = changeDraft { r -> r.copy(blocks = r.blocks.map { if (it.id == id) it.withValue(key,value) else it }) }
    fun updateBlockValues(id: String,values: Map<String,String>) = changeDraft { r -> r.copy(blocks = r.blocks.map { if (it.id == id) it.copy(values = it.values + values) else it }) }
    fun removeBlock(id: String) = changeDraft { it.copy(blocks = it.blocks.filterNot { b -> b.id == id }) }
    fun moveBlock(id: String,delta: Int) = changeDraft { r ->
        val blocks = r.blocks.toMutableList();val from = blocks.indexOfFirst { it.id == id };val to = from+delta
        if (from in blocks.indices && to in blocks.indices) blocks.add(to,blocks.removeAt(from))
        r.copy(blocks = blocks)
    }
    fun save() {
        val current = state.value;val draft = current.draft ?: return
        if (current.saving) return
        if (!validRecord(draft)) { mutable.update { it.copy(message = "Добавьте описание, название или заполните любой блок.") };return }
        mutable.update { it.copy(saving = true) }
        val record = draft.copy(title = draft.title.trim(),description = draft.description.trimEnd(),updatedAt = maxOf(System.currentTimeMillis(),draft.updatedAt+1))
        enqueue {
            repository.save(record);saved["selectedDate"] = record.date;saved["screen"] = Screen.JOURNAL.name
            mutable.update { it.copy(draft = null,saving = false,selectedDate = record.date,screen = Screen.JOURNAL,highlightId = record.id,
                calls = sortCalls(it.calls.filterNot { c -> c.id == record.id } + record),message = "Вызов сохранён") }
        }
    }
    fun discardDraft() {
        if (state.value.saving) return
        mutable.update { it.copy(saving = true) }
        enqueue { repository.discardDraft();mutable.update { it.copy(draft = null,saving = false) };navigate(Screen.JOURNAL) }
    }
    fun deleteOpened() {
        val id = state.value.openedId ?: return
        if (state.value.saving) return
        mutable.update { it.copy(saving = true) }
        enqueue {
            repository.delete(id);if (state.value.draft?.id == id) repository.discardDraft()
            mutable.update { it.copy(saving = false,calls = it.calls.filterNot { c -> c.id == id },draft = it.draft?.takeUnless { d -> d.id == id },message = "Вызов удалён") }
            navigate(state.value.detailOrigin)
        }
    }
    fun openSection(screen: Screen) { require(screen in listOf(Screen.JOURNAL,Screen.MEMORY,Screen.MORE,Screen.PROFILE,Screen.REFERENCE,Screen.MKB));navigate(screen) }
    suspend fun awaitPendingWrites() { val done=kotlinx.coroutines.CompletableDeferred<Unit>();enqueue { done.complete(Unit) };done.await() }
    suspend fun reloadAfterRestore() { val draft=repository.draft();val date=repository.selectedDate() ?: state.value.selectedDate
        saved["selectedDate"]=date;mutable.update { it.copy(draft=draft?.first,draftIsNew=draft?.second ?: true,selectedDate=date) }
    }
    fun openSearch() = navigate(Screen.SEARCH)
    fun search(query: String) { saved["query"] = query;mutable.update { it.copy(query = query) } }
    fun clearMessage() { mutable.update { it.copy(message = null) } }
    fun back() {
        if (state.value.saving) return
        when(state.value.screen) {
            Screen.EDITOR -> if (state.value.draft?.hasContent == false) discardDraft() else navigate(Screen.JOURNAL)
            Screen.DETAIL -> navigate(state.value.detailOrigin)
            Screen.SEARCH,Screen.MEMORY,Screen.MORE,Screen.PROFILE,Screen.REFERENCE,Screen.MKB -> navigate(Screen.JOURNAL)
            Screen.JOURNAL -> Unit
        }
    }
}
