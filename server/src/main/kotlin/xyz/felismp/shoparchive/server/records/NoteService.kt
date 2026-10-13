package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.CreatedNote
import xyz.felismp.shoparchive.api.EventService
import xyz.felismp.shoparchive.api.NoteCreatedEvent
import xyz.felismp.shoparchive.api.NoteService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.api.ServiceRegistry
import xyz.felismp.shoparchive.api.ShopEvents
import xyz.felismp.shoparchive.server.Log
import xyz.felismp.shoparchive.server.config.ConfigService
import xyz.felismp.shoparchive.shared.CreateNoteRequest
import xyz.felismp.shoparchive.shared.ErrorReasons
import xyz.felismp.shoparchive.shared.MarkNotesReadRequest
import xyz.felismp.shoparchive.shared.NoteChangedMessage
import xyz.felismp.shoparchive.shared.NoteDto
import xyz.felismp.shoparchive.shared.NoteRead
import xyz.felismp.shoparchive.shared.NotesUnread
import xyz.felismp.shoparchive.shared.UserRef
import xyz.felismp.shoparchive.shared.isIsoDate
import xyz.felismp.shoparchive.shared.isUuidV7
import java.time.Clock
import java.time.LocalDate

internal class DefaultNoteService(
    private val config: ConfigService,
    private val branches: BranchStore,
    private val writer: RecordWriter,
    private val notes: NoteStore,
    private val reads: NoteReadStore,
    private val sessions: SessionStore,
    private val access: Access,
    private val services: ServiceRegistry,
    private val clock: Clock = Clock.systemUTC(),
) : NoteService {
    override fun create(principal: Principal, request: CreateNoteRequest): CreatedNote {
        access.require(principal, NOTE_CREATE_NODE)
        if (!isUuidV7(request.id)) throw badRequest("The id must be a UUID version 7 in lower case.")
        access.requireBranch(principal, request.branch)
        val branch = branches.find(request.branch) ?: throw badRequest("There is no branch '${request.branch}'.", ErrorReasons.BRANCH_UNKNOWN)
        val text = request.text.trim()
        if (text.isEmpty()) throw badRequest("A note needs some text.")
        val (stored, isNew) = writer.run {
            notes.find(request.id)?.let { existing ->
                // A resend gets what was stored the first time; an id that is someone else's, or another branch's, is not handed out.
                if (existing.createdBy.id != principal.userId || existing.branch != branch.key) throw conflict("That note id is already used.")
                return@run existing to false
            }
            if (branch.archived) throw badRequest("The branch '${branch.key}' is archived.", ErrorReasons.BRANCH_ARCHIVED)
            // The day it is written on is the day the open session was opened, even after midnight; like an entry's.
            val date = sessions.openFor(branch.key)?.businessDate ?: businessToday(clock, config)
            notes.save(Note(request.id, branch.key, date, text, UserRef(principal.userId, principal.username), stamp(clock, config))) to true
        }
        if (isNew) {
            push(stored.branch, stored.date, setOf(stored.createdBy.id))
            publishEvent(createdEvent(stored))
        }
        return CreatedNote(dto(stored), isNew)
    }

    override fun list(principal: Principal, branch: String, date: String): List<NoteDto> {
        val day = day(date)
        access.requireBranch(principal, branch)
        val readAll = access.has(principal, NOTE_READ_NODE)
        if (!readAll && !access.has(principal, NOTE_CREATE_NODE)) throw access.forbidden("This needs the permission $NOTE_READ_NODE.", ErrorReasons.PERMISSION_MISSING)
        return notes.onDay(branch, day).filter { readAll || it.createdBy.id == principal.userId }.map(::dto)
    }

    override fun markRead(principal: Principal, id: String): NoteDto {
        access.require(principal, NOTE_READ_NODE)
        val note = notes.find(id)?.takeIf { isUuidV7(id) } ?: throw notFound("No such note.")
        access.requireBranch(principal, note.branch)
        val fresh = writer.run {
            (reads.readAt(principal.userId, id) == null).also { reads.mark(principal.userId, principal.username, listOf(id), stamp(clock, config)) }
        }
        if (fresh) push(note.branch, note.date, setOf(note.createdBy.id))
        return dto(note)
    }

    override fun markAllRead(principal: Principal, request: MarkNotesReadRequest): List<NoteDto> {
        access.require(principal, NOTE_READ_NODE)
        val day = day(request.date)
        access.requireBranch(principal, request.branch)
        val marked = writer.run {
            val unread = unreadOf(principal, request.branch, day)
            reads.mark(principal.userId, principal.username, unread.map { it.id }, stamp(clock, config))
            unread
        }
        if (marked.isNotEmpty()) push(request.branch, day, marked.map { it.createdBy.id }.toSet())
        return list(principal, request.branch, request.date)
    }

    override fun unread(principal: Principal, branch: String, date: String): NotesUnread {
        access.require(principal, NOTE_READ_NODE)
        val day = day(date)
        access.requireBranch(principal, branch)
        return NotesUnread(unreadOf(principal, branch, day).size)
    }

    /** The notes of others on the day that [principal] has not read. */
    private fun unreadOf(principal: Principal, branch: String, day: LocalDate) =
        notes.onDay(branch, day).filter { it.createdBy.id != principal.userId && reads.readAt(principal.userId, it.id) == null }

    private fun day(text: String): LocalDate {
        if (!isIsoDate(text)) throw badRequest("'$text' is not a date like 2026-09-27.", ErrorReasons.DATE_INVALID)
        return LocalDate.parse(text)
    }

    private fun dto(note: Note) = NoteDto(
        note.id, note.branch, note.date.toString(), note.text, note.createdBy, note.createdAt,
        reads.readers(note.id).map { (reader, at) -> NoteRead(UserRef(reader.id, reader.name), at) },
    )

    /** The event of [note] being written, with the branch's name as it is now. */
    fun createdEvent(note: Note) = NoteCreatedEvent(dto(note), branches.find(note.branch)?.displayName ?: note.branch)

    /** Tells the users of the branch who hold `note.read`, and the [authors] (their note's reads changed): an open Notes screen or Today card asks again. */
    private fun push(branch: String, date: LocalDate, authors: Set<String>) {
        try {
            services.get(EventService::class.java)?.broadcastTo(NoteChangedMessage(branch, date.toString())) {
                access.canBranch(it, branch) && (it.userId in authors || access.has(it, NOTE_READ_NODE))
            }
        } catch (e: Exception) {
            Log.warn("could not push ${NoteChangedMessage::class.simpleName}: ${e.message}")
        }
    }

    /** Tells the listeners of the shop events, after the note is saved. They cannot fail the request that already wrote it. */
    private fun publishEvent(event: NoteCreatedEvent) {
        try {
            services.get(ShopEvents::class.java)?.publish(event)
        } catch (e: Exception) {
            Log.warn("could not publish ${event.type}: ${e.message}")
        }
    }
}
