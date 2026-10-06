package xyz.felismp.shoparchive.launcher

/** A blocking (refuse to start) or advisory (warn and continue) problem with the root's filesystem. */
sealed class Problem(val message: String) {
    class Refuse(message: String) : Problem(message)
    class Warn(message: String) : Problem(message)
}

private val UNSUPPORTED_LOCAL_TYPES = setOf("fat", "fat32", "vfat", "msdos", "exfat")
private val NETWORK_TYPES = setOf("cifs", "smbfs", "smb2", "smb3", "nfs", "nfs4", "afs", "9p", "davfs", "webdav", "fuse.sshfs")

// WSL1 reports a Windows drive mount (e.g. /mnt/c) as drvfs, not 9p; some plan9 mounts report v9fs
// instead of 9p. All three share the same broken file locking / non-atomic rename, but they are a
// Windows drive mount, not a "network filesystem" in the sense an admin would recognize - worded and
// checked separately so the message matches what someone running from /mnt/c under WSL actually sees.
private val WINDOWS_DRIVE_MOUNT_TYPES = setOf("9p", "drvfs", "v9fs")

private val SD_CARD_DEVICE = Regex("/dev/mmcblk\\d+.*")

// Known gap, not fixable from the filesystem type alone: exFAT mounted through exfat-fuse reports type
// `fuseblk`, which ntfs-3g also uses for a perfectly fine NTFS mount - refusing by type would reject
// both or neither.

// A network share mapped to a drive letter (e.g. Z: -> \\nas\share) looks like a local NTFS volume to
// the JVM, so neither this check nor FileStore can detect it - the admin has to know to avoid that.
private const val DRIVE_LETTER_GAP =
    "Note: a share mapped to a drive letter (e.g. Z: -> \\\\nas\\share) looks local to the JVM and can't be caught this way - avoid that too."

/**
 * Refuses a root [path] we can recognize as unsupported from the string alone, with no filesystem call.
 * A Windows UNC path needs none - which matters because [java.nio.file.FileStore] can throw for exactly
 * these paths (a DFS namespace, an ACL-restricted share), and that failure must never be read as "no
 * problem, continue". Returns the refusal message, or null if [path] isn't recognized as unsupported.
 */
fun unsupportedRootPath(path: String): String? = when {
    path.startsWith("\\\\") ->
        "root is a Windows network path (UNC): $path. " +
            "ShopArchive needs a local disk for atomic writes and file locking. $DRIVE_LETTER_GAP"
    else -> null
}

/**
 * Pure decision for whether [path]'s filesystem (of [type], device/share [name]) is safe for
 * ShopArchive's root. Kept free of real I/O (no [java.nio.file.FileStore]) so it is unit-testable
 * with fabricated inputs instead of a real filesystem. UNC paths are refused earlier by
 * [unsupportedRootPath], before a [java.nio.file.FileStore] lookup (and this function) is ever reached.
 */
fun fileStoreProblem(type: String, name: String, path: String): Problem? {
    val lowerType = type.lowercase()
    return when {
        lowerType in UNSUPPORTED_LOCAL_TYPES ->
            Problem.Refuse(
                "root is on a $type filesystem: $path. " +
                    "ShopArchive needs a local disk (atomic writes + file locking), which $type does not support."
            )
        lowerType in WINDOWS_DRIVE_MOUNT_TYPES ->
            Problem.Refuse(
                "root is on a Windows drive mount ($type): $path. WSL1 reports a Windows drive (e.g. /mnt/c) " +
                    "this way, and so do some plan9 mounts - not a remote share, but the same broken file " +
                    "locking and non-atomic rename apply. ShopArchive needs a local disk."
            )
        lowerType in NETWORK_TYPES ->
            Problem.Refuse(
                "root is on a network filesystem ($type): $path. " +
                    "ShopArchive needs a local disk (atomic writes + file locking). $DRIVE_LETTER_GAP"
            )
        SD_CARD_DEVICE.matches(name) ->
            Problem.Warn(
                "root looks like it's on an SD card ($name): $path. " +
                    "SD cards wear out faster than an SSD under a server's write load."
            )
        else -> null
    }
}
