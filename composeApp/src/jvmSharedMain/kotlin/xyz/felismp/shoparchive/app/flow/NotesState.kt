package xyz.felismp.shoparchive.app.flow

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import xyz.felismp.shoparchive.shared.CreateNoteRequest
import xyz.felismp.shoparchive.shared.NoteChangedMessage
import xyz.felismp.shoparchive.shared.NoteDto
import java.time.LocalDate

/**
 * [notes] are the day's notes, newest first (null before the first answer); a reload keeps them on screen. [failure] says why they are not there,
 * [markFailure] why the last "mark as read" was refused, [postFailure] why the note was not posted (the [draft] stays). [userId] is the person's own id.
 */
data class NotesUi(
    val branch: String?,
    val date: String,
    val isToday: Boolean,
    val loading: Boolean,
    val notes: List<NoteDto>?,
    val failure: Failure?,
    val canCreate: Boolean,
    val canRead: Boolean,
    val draft: String,
    val posting: Boolean,
    val postFailure: Failure?,
    val markFailure: Failure?,
    /** The ids being marked read right now. */
    val marking: Set<String>,
    val userId: String,
    /** The live connection is up: posting and marking need it. */
    val connected: Boolean,
) {
    /** A note of someone else that the person (holding the read node) has not read: what the unread marker and "mark all read" are about. */
    fun unread(note: NoteDto): Boolean = canRead && note.createdBy.id != userId && note.reads.none { it.by.id == userId }

    val unreadCount: Int get() = notes.orEmpty().count(::unread)

    /** When the person read [note], if they did. */
    fun readAt(note: NoteDto): String? = note.reads.firstOrNull { it.by.id == userId }?.at

    /** Post is on: some text, not already posting, connected, and a branch. */
    val canPost: Boolean get() = canCreate && branch != null && draft.isNotBlank() && !posting && connected
}

/**
 * Notes: the day notes staff leave for the owner, one day of the chosen branch at a time (today first). Reloads on a branch or day change, a reconnect,
 * a change of rights and the live message that a note of the shown day was written or read. The server decides what the person may see and do.
 */
class NotesState(private val env: Env) {
    private val date = MutableStateFlow(env.today())
    private val notes = MutableStateFlow<List<NoteDto>?>(null)
    private val loading = MutableStateFlow(false)
    private val failure = MutableStateFlow<Failure?>(null)
    private val draft = MutableStateFlow("")
    private val posting = MutableStateFlow(false)
    private val postFailure = MutableStateFlow<Failure?>(null)
    private val markFailure = MutableStateFlow<Failure?>(null)
    private val marking = MutableStateFlow<Set<String>>(emptySet())
    /** Counts the loads, so only the latest one's answer is kept. */
    private val loads = java.util.concurrent.atomic.AtomicInteger()

    /** Made when a post starts and kept while the same text is sent again, so a resend after a lost answer is the same note. */
    private var pendingId: String? = null

    private val _ui = MutableStateFlow(snapshot())
    val ui: StateFlow<NotesUi> = _ui.asStateFlow()

    init {
        env.scope.launch {
            merge(
                // Another branch's notes are not shown, nor acted on, while its own are on the way.
                env.branch.filterNotNull().onEach { clearDay() }.map { },
                date.map { }.drop(1), // the first value is the day the first load already sees
                env.refetch,
                env.config.map { env.can.noteCreate to env.can.noteRead }.distinctUntilChanged().drop(1).map { },
                env.messages.filter { it is NoteChangedMessage && it.branch == env.branch.value && it.date == date.value.toString() }.map { },
            ).collectLatest { load() }
        }
        env.scope.launch { env.canWrite.collect { publish() } }
    }

    private fun snapshot(): NotesUi {
        val can = env.can
        return NotesUi(
            env.branch.value, date.value.toString(), date.value == env.today(), loading.value, notes.value, failure.value, can.noteCreate, can.noteRead,
            draft.value, posting.value, postFailure.value, markFailure.value, marking.value, can.userId, env.canWrite.value,
        )
    }

    private fun publish() {
        _ui.value = snapshot()
    }

    /** Asks for the day again (after a failure, or the refresh a screen wants). */
    fun reload() {
        env.scope.launch { load() }
    }

    private suspend fun load() {
        val branch = env.branch.value
        if (branch == null || !(env.can.noteCreate || env.can.noteRead)) {
            notes.value = null
            failure.value = null
            loading.value = false
            publish()
            return
        }
        loading.value = true
        publish()
        val day = date.value
        // Another load (a post, a reload, a change of day) may run beside this one: only the latest one's answer is kept,
        // so neither another day's list nor an older list of this day replaces what is shown.
        val ticket = loads.incrementAndGet()
        fun stillShown() = loads.get() == ticket && env.branch.value == branch && date.value == day
        try {
            val list = sorted(env.calls.run { env.api.notes(branch, day.toString()) })
            if (!stillShown()) return
            notes.value = list
            failure.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!stillShown()) return
            failure.value = e.toFailure()
        }
        loading.value = false
        publish()
    }

    private fun sorted(list: List<NoteDto>) = list.sortedWith(compareByDescending<NoteDto> { it.createdAt }.thenByDescending { it.id })

    // ---- the day ----

    fun previousDay() = show(date.value.minusDays(1))

    /** Not past today: a note is never written for a day that has not come. */
    fun nextDay() {
        val next = date.value.plusDays(1)
        if (!next.isAfter(env.today())) show(next)
    }

    /** Shows [day] (`yyyy-MM-dd`), e.g. the open day Today names. A text that is not a date is ignored. */
    fun show(day: String) {
        runCatching { LocalDate.parse(day) }.getOrNull()?.let(::show)
    }

    private fun show(day: LocalDate) {
        if (day == date.value) return
        clearDay()
        date.value = day // after the clearing: it starts the load
        publish()
    }

    /** Forgets what was shown of the branch and day that were: its notes, what went wrong with them and what was being marked. */
    private fun clearDay() {
        notes.value = null
        failure.value = null
        markFailure.value = null
        marking.value = emptySet()
        publish()
    }

    // ---- writing ----

    fun setDraft(text: String) {
        if (text == draft.value) return
        draft.value = text
        pendingId = null // other words are another note
        publish()
    }

    /** Posts the draft; also the retry after a failure. The draft stays until the server has the note. */
    fun post() {
        val branch = env.branch.value
        val text = draft.value.trim()
        if (branch == null || !env.can.noteCreate || text.isEmpty() || posting.value || !env.canWrite.value) return
        val id = pendingId ?: uuidV7(env.now()).also { pendingId = it }
        posting.value = true
        postFailure.value = null
        publish()
        env.scope.launch {
            try {
                val note = env.calls.run { env.api.createNote(CreateNoteRequest(id, branch, text)) }
                draft.value = ""
                pendingId = null
                // The note is on the day the server put it on (the open day, which can be the day before): show that one.
                if (env.branch.value == branch) show(note.date)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                postFailure.value = e.toFailure()
            }
            posting.value = false
            publish()
            load()
        }
    }

    // ---- reading ----

    /** Marks [id] as read by the person. */
    fun markRead(id: String) {
        if (!env.can.noteRead || id in marking.value || !env.canWrite.value) return
        mark(setOf(id)) { env.api.markNoteRead(id).let { done -> notes.value.orEmpty().map { if (it.id == done.id) done else it } } }
    }

    /** Marks every note of others on the shown day as read. */
    fun markAllRead() {
        val branch = env.branch.value
        if (branch == null || !env.can.noteRead || !env.canWrite.value || marking.value.isNotEmpty()) return
        val day = date.value.toString()
        mark(notes.value.orEmpty().map { it.id }.toSet().ifEmpty { return }) { env.api.markNotesRead(branch, day) }
    }

    /** The answer is the day's notes as they are now; it is kept only if the branch and day it is about are still the ones shown, else the screen is asked again. */
    private fun mark(ids: Set<String>, call: suspend () -> List<NoteDto>) {
        val branch = env.branch.value
        val day = date.value
        marking.value = marking.value + ids
        markFailure.value = null
        publish()
        env.scope.launch {
            try {
                val answer = env.calls.run { call() }
                if (env.branch.value == branch && date.value == day) notes.value = sorted(answer) else load()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (env.branch.value == branch && date.value == day) markFailure.value = e.toFailure()
            }
            marking.value = marking.value - ids
            publish()
        }
    }
}
