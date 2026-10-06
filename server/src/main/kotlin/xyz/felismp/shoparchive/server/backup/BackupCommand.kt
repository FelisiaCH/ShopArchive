package xyz.felismp.shoparchive.server.backup

import xyz.felismp.shoparchive.api.Command
import xyz.felismp.shoparchive.api.CommandRegistry
import xyz.felismp.shoparchive.api.CommandSender
import java.nio.file.Path

/** `backup` and `backup restore-slips <folder>`. The work is [BackupService]'s; this only turns it into console lines. */
internal fun registerBackupCommand(commands: CommandRegistry, service: BackupService, root: Path) {
    commands.register(object : Command {
        override val name = "backup"
        override val description = "Make a backup now (backup restore-slips <folder>: put slips back after a restore)"

        override fun complete(args: List<String>) = if (args.size == 1) listOf("restore-slips").filter { it.startsWith(args[0]) } else emptyList()

        override fun execute(sender: CommandSender, args: List<String>) {
            when {
                args.isEmpty() -> backupNow(sender)
                args[0] == "restore-slips" && args.size == 2 -> restoreSlips(sender, args[1])
                else -> sender.sendMessage("Usage: backup | backup restore-slips <folder with the slip images>")
            }
        }

        private fun backupNow(sender: CommandSender) {
            sender.sendMessage("Backup started...")
            when (val outcome = service.run()) {
                null -> sender.sendMessage("A backup is already running.")
                is BackupOutcome.Failed -> sender.sendMessage("Backup FAILED: ${outcome.reason}")
                is BackupOutcome.Ok -> {
                    sender.sendMessage("Backup done: backups/${outcome.zip}, ${formatSize(outcome.sizeBytes)}, ${outcome.newSlips} new slips")
                    when (val copy = outcome.copy) {
                        is CopyOutcome.Done -> sender.sendMessage("Copied to the second folder: ${copy.zips} zips, ${copy.slips} slips")
                        is CopyOutcome.Failed -> sender.sendMessage("Copy to the second folder FAILED: ${copy.reason}")
                        is CopyOutcome.NotConfigured -> Unit
                    }
                }
            }
        }

        private fun restoreSlips(sender: CommandSender, folder: String) {
            val report = try {
                service.restoreSlips(root.resolve(folder))
            } catch (e: BackupException) {
                return sender.sendMessage("Restore failed: ${e.message}")
            }
            sender.sendMessage("Slips: ${report.restored} put back, ${report.alreadyThere} were there already, ${report.problems.size} problems")
            report.problems.forEach { sender.sendMessage("  $it") }
        }
    }, "core")
}
