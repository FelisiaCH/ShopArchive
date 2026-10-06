package xyz.felismp.shoparchive.launcher

import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileStoreCheckTest {
    @Test
    fun refusesWindowsUncPathWithoutTouchingTheFilesystem() {
        // Pure string matching, no java.nio.file.FileStore lookup - Files.getFileStore(root) can throw
        // for exactly these paths (DFS namespace, ACL-restricted share), so this can't depend on it.
        assertTrue(unsupportedRootPath("\\\\nas\\share\\srv") != null)
        assertNull(unsupportedRootPath("C:\\srv"))
        assertNull(unsupportedRootPath("/srv"))
    }

    @Test
    fun refusesFat32AndExfat() {
        // On Windows, FileStore.name() is the volume LABEL (e.g. "Coding,Gaming & Vid"), not a device path.
        assertTrue(fileStoreProblem("FAT32", "USB DISK", "E:\\srv") is Problem.Refuse)
        assertTrue(fileStoreProblem("exfat", "/dev/sdb1", "/mnt/usb/srv") is Problem.Refuse)
    }

    @Test
    fun refusesNetworkFilesystems() {
        assertTrue(fileStoreProblem("cifs", "share", "/mnt/nas/srv") is Problem.Refuse)
        assertTrue(fileStoreProblem("nfs4", "nas:/export/srv", "/mnt/nfs/srv") is Problem.Refuse)
    }

    @Test
    fun refusesWindowsDriveMountsUnderWslOrPlan9() {
        // drvfs (WSL1's /mnt/c), v9fs and 9p all share the same broken locking/rename as a real network
        // filesystem, but the message for them must say "Windows drive mount", not "network filesystem".
        assertTrue(fileStoreProblem("9p", "hostshare", "/mnt/9p/srv") is Problem.Refuse)
        assertTrue(fileStoreProblem("drvfs", "C:\\", "/mnt/c/srv") is Problem.Refuse)
        assertTrue(fileStoreProblem("v9fs", "hostshare", "/mnt/host/srv") is Problem.Refuse)

        val problem = fileStoreProblem("drvfs", "C:\\", "/mnt/c/srv")
        assertTrue(problem is Problem.Refuse)
        assertTrue(problem.message.contains("Windows drive mount"))
        assertTrue(!problem.message.contains("network filesystem"))
    }

    @Test
    fun acceptsLocalFilesystems() {
        // On Windows, FileStore.name() is the volume label (e.g. "Coding,Gaming & Vid") or "" when
        // unlabeled - never a device path like \\Device\HarddiskVolume3.
        assertNull(fileStoreProblem("NTFS", "Coding,Gaming & Vid", "C:\\srv"))
        assertNull(fileStoreProblem("NTFS", "", "C:\\srv"))
        assertNull(fileStoreProblem("ext4", "/dev/sda1", "/srv"))
        assertNull(fileStoreProblem("apfs", "/dev/disk1s1", "/Users/x/srv"))
    }

    @Test
    fun warnsOnSdCard() {
        assertTrue(fileStoreProblem("ext4", "/dev/mmcblk0p2", "/mnt/sdcard/srv") is Problem.Warn)
    }
}
