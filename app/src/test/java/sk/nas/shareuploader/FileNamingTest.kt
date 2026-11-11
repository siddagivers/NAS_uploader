package sk.nas.shareuploader

import org.junit.Assert.*
import org.junit.Test

class FileNamingTest {
    @Test fun sanitizesUnsafeAndDirectoryNames() {
        assertEquals("file_photo.jpg", FileNaming.sanitize("C:\\stuff\\file:photo.jpg"))
        assertEquals("file", FileNaming.sanitize(".."))
        assertEquals("summer.jpg", FileNaming.sanitize("/storage/emulated/0/summer.jpg"))
    }
    @Test fun validatesFolderSegments() {
        assertTrue(FileNaming.validFolder("copy"))
        assertTrue(FileNaming.validFolder("copy\\photos"))
        assertFalse(FileNaming.validFolder("copy\\..\\secret"))
        assertFalse(FileNaming.validFolder("bad?name"))
    }
    @Test fun createsUniqueNames() {
        val name = FileNaming.uniqueName("picture.png")
        assertTrue(name.endsWith("_picture.png"))
        assertTrue(name.length < 170)
    }
    @Test fun linkNeedsBaseUrl() {
        assertNull(FileNaming.link("", "a b.txt") { it })
        assertEquals("https://example.com/copy/a%20b.txt", FileNaming.link("https://example.com/copy/", "a b.txt") { it.replace(" ", "%20") })
    }
}
