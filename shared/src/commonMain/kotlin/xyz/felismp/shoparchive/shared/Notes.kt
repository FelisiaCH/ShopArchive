package xyz.felismp.shoparchive.shared

import kotlinx.serialization.Serializable

// --- day notes: `/api/v1/notes` ---

/** [by] read the note at [at] (a time with its offset). */
@Serializable
data class NoteRead(val by: UserRef, val at: String)

/** A note staff left for the owner on [date], the business date of [branch]'s day it was written on. [reads] are who read it, in the order they did. */
@Serializable
data class NoteDto(
    val id: String,
    val branch: String,
    val date: String,
    val text: String,
    val createdBy: UserRef,
    val createdAt: String,
    val reads: List<NoteRead> = emptyList(),
)

/** Body of `POST /api/v1/notes`. [id] is a UUIDv7 the client makes, so a resend is the same note. */
@Serializable
data class CreateNoteRequest(val id: String, val branch: String, val text: String)

/** Body of `POST /api/v1/notes/read`: marks every note of [branch] on [date] that others wrote as read. */
@Serializable
data class MarkNotesReadRequest(val branch: String, val date: String)

/** Answer of `GET /api/v1/notes/unread`: the notes of others on one day that the caller has not read. */
@Serializable
data class NotesUnread(val unread: Int)
