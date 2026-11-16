package app.tennyo.mobile.feature.local

import app.tennyo.mobile.feature.git.GitCredentials
import app.tennyo.mobile.feature.git.GitFault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.eclipse.jgit.api.errors.TransportException
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WorkspaceFilesTest {
    private val root = Files.createTempDirectory("tennyo-workspace-files").toFile()
    private val files = WorkspaceFiles(
        FileSystem.SYSTEM,
        File(root, "workspaces").toOkioPath(),
        File(root, "python-packages").toOkioPath(),
        Dispatchers.Unconfined,
    )

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun resolvesPathsInsideTheWorkspace() {
        File(root, "workspaces/demo/src").mkdirs()

        assertEquals(File(root, "workspaces/demo/src/Main.kt").toOkioPath(), files.resolve("demo", "src/Main.kt"))
    }

    @Test
    fun rejectsParentSegmentsAndAbsolutePaths() {
        File(root, "workspaces/demo").mkdirs()

        listOf("../other/secret.txt", "src/../../other", "/etc/passwd", "C:\\Windows\\win.ini").forEach { path ->
            assertFailsWith<WorkspaceFault.PathRejected>(path) { files.resolve("demo", path) }
        }
    }

    @Test
    fun createsFilesAndFoldersOnceAndOnlyInsideTheWorkspace(): Unit = runBlocking {
        File(root, "workspaces/demo").mkdirs()

        files.createEntry("demo", "pkg/util.py", directory = false)
        files.createEntry("demo", "data", directory = true)

        assertEquals("", File(root, "workspaces/demo/pkg/util.py").readText())
        assertTrue(File(root, "workspaces/demo/data").isDirectory)
        assertFailsWith<WorkspaceFault.AlreadyExists> { files.createEntry("demo", "pkg/util.py", directory = false) }
        assertFailsWith<WorkspaceFault.PathRejected> { files.createEntry("demo", "../escape.py", directory = false) }
        assertFailsWith<WorkspaceFault.PathRejected> { files.createEntry("demo", "", directory = true) }
    }

    @Test
    fun rejectsASlugThatIsNotADirectoryName() {
        assertFailsWith<WorkspaceFault.PathRejected> { files.resolve("../demo", "a.txt") }
    }

    @Test
    fun rejectsASymlinkThatLeavesTheWorkspace() {
        val outside = File(root, "outside").apply { mkdirs() }
        File(outside, "secret.txt").writeText("secret")
        val workspace = File(root, "workspaces/demo").apply { mkdirs() }
        val linked = runCatching { Files.createSymbolicLink(File(workspace, "link").toPath(), outside.toPath()) }
        assumeTrue("Creating symlinks needs a privilege this machine lacks", linked.isSuccess)
        // The JVM on Windows canonicalizes without following links, unlike Android, so there is nothing to check.
        assumeTrue("This JVM does not resolve links", File(workspace, "link").canonicalFile == outside.canonicalFile)

        assertFailsWith<WorkspaceFault.PathRejected> { files.resolve("demo", "link/secret.txt") }
    }

    @Test
    fun readsWhatItWrites(): Unit = runBlocking {
        files.writeText("demo", "src/Main.kt", "fun main() = Unit\n")

        assertEquals("fun main() = Unit\n", files.readText("demo", "src/Main.kt"))
        assertEquals(listOf("src"), files.list("demo", "").map { it.name })
        assertFailsWith<WorkspaceFault.NotFound> { files.readText("demo", "missing.kt") }
    }

    @Test
    fun refusesToReadAFileOverTheLimit(): Unit = runBlocking {
        files.writeText("demo", "big.txt", "x".repeat(10))

        assertEquals("x".repeat(10), files.readText("demo", "big.txt", maxBytes = 10))
        assertFailsWith<WorkspaceFault.Unreadable> { files.readText("demo", "big.txt", maxBytes = 9) }
    }

    @Test
    fun deletingAWorkspaceDeletesItsPackagesAndTheInstallersLeftovers(): Unit = runBlocking {
        File(root, "workspaces/demo").mkdirs()
        val leftovers = listOf("demo", "demo.staging", "demo.new", "demo.old").map { name ->
            File(root, "python-packages/$name").apply {
                mkdirs()
                File(this, "tennyo-packages.json").writeText("{}")
            }
        }
        val neighbour = File(root, "python-packages/demo-2").apply { mkdirs() }

        files.delete("demo")

        assertTrue(leftovers.none { it.exists() })
        assertTrue(neighbour.isDirectory)
        assertTrue(!File(root, "workspaces/demo").exists())
    }

    @Test
    fun packagePathsAreRejectedForASlugThatIsNotADirectoryName() {
        assertFailsWith<WorkspaceFault.PathRejected> { files.packages("../x") }
        assertFailsWith<WorkspaceFault.PathRejected> { files.packages("a/b") }
        assertFailsWith<WorkspaceFault.PathRejected> { files.packagesStaging("../x") }
    }

    @Test
    fun aRecordWrittenToStagingReadsBackOnceItIsSwappedIn(): Unit = runBlocking {
        val record = PackagesRecord(requirements = listOf("Markdown==2.6.11"), added = listOf("python-frontmatter"))
        assertNull(files.readPackagesRecord("demo"))

        files.writePackagesRecord(files.packagesStaging("demo"), record)
        assertNull(files.readPackagesRecord("demo"))

        assertTrue(File(root, "python-packages/demo.staging").renameTo(File(root, "python-packages/demo")))
        assertEquals(record, files.readPackagesRecord("demo"))
    }

    @Test
    fun aRecordThatCannotBeParsedReadsAsNone(): Unit = runBlocking {
        File(root, "python-packages/demo").mkdirs()
        File(root, "python-packages/demo/tennyo-packages.json").writeText("{ not json")

        assertNull(files.readPackagesRecord("demo"))
    }
}

class LocalWorkspaceStoreTest {
    private val root = Files.createTempDirectory("tennyo-workspace-store").toFile()
    private val files = WorkspaceFiles(
        FileSystem.SYSTEM,
        File(root, "workspaces").toOkioPath(),
        File(root, "python-packages").toOkioPath(),
        Dispatchers.Unconfined,
    )
    private val index = File(root, "workspace-index.json")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun workspacesAndTheirTabsSurviveANewStore(): Unit = runBlocking {
        val workspace = store().reserve("My App")
        store().updateTabs(workspace.slug, listOf("README.md"), selectedTab = 0)

        val reloaded = store().get("my-app")

        assertEquals("My App", reloaded?.name)
        assertEquals(listOf("README.md"), reloaded?.openedTabs)
        assertTrue(File(root, "workspaces/my-app").isDirectory)
    }

    @Test
    fun aTruncatedIndexIsRebuiltFromTheWorkspaceDirectories(): Unit = runBlocking {
        store().reserve("Notes")
        index.writeText(index.readText().take(10))

        assertEquals(listOf("notes"), store().workspaces.first().map { it.slug })
    }

    @Test
    fun aWorkspaceWhoseDirectoryIsGoneIsForgotten(): Unit = runBlocking {
        store().reserve("Gone")
        File(root, "workspaces/gone").deleteRecursively()

        assertNull(store().get("gone"))
    }

    @Test
    fun aNameInUseGetsANumberedSlug(): Unit = runBlocking {
        val store = store()

        assertEquals(listOf("app", "app-2", "app-3"), List(3) { store.reserve("App").slug })
    }

    private fun store() = JsonLocalWorkspaceStore(
        fileSystem = FileSystem.SYSTEM,
        indexFile = index.toOkioPath(),
        files = files,
        now = { 1L },
        ioContext = Dispatchers.Unconfined,
    )
}

class WorkspaceSlugTest {
    @Test
    fun keepsOnlyLowercaseLettersDigitsAndSingleDashes() {
        assertEquals("my-cool-app-2", workspaceSlug("  My Cool  App (2)! ", emptySet()))
        assertEquals("workspace", workspaceSlug("***", emptySet()))
    }

    @Test
    fun suffixesACollisionWithTheFirstFreeNumber() {
        assertEquals("demo-3", workspaceSlug("Demo", setOf("demo", "demo-2")))
    }
}

class GitCredentialStoreTest {
    private val root = Files.createTempDirectory("tennyo-git-credentials").toFile()
    private val file = File(root, "credentials.bin")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun credentialsRoundTripEncryptedAndCanBeDeleted(): Unit = runBlocking {
        store().save("GitHub.com", GitCredentials("octo", "token-123"))

        assertEquals("octo", store().get("github.com")?.username)
        assertEquals("token-123", store().get("github.com")?.secret)
        assertTrue("token-123" !in file.readBytes().decodeToString(), "the secret is not stored in plain text")
        assertEquals(listOf("github.com"), store().hosts.first())

        store().delete("github.com")
        assertNull(store().get("github.com"))
    }

    @Test
    fun credentialsThatNoLongerDecryptReadAsNone(): Unit = runBlocking {
        store().save("github.com", GitCredentials("octo", "token"))

        val otherKey = store(XorCipher(0x5A))

        assertNull(otherKey.get("github.com"))
    }

    @Test
    fun aRefusedLoginMapsToAuthRequiredForItsHost() {
        val refused = TransportException("https://github.com/team/app.git: not authorized")
        val unreachable = TransportException("https://github.com/team/app.git: connection refused")

        assertEquals(GitFault.AuthRequired("github.com"), refused.toTransferFault("github.com", cancelled = false))
        assertEquals(GitFault.Network, unreachable.toTransferFault("github.com", cancelled = false))
        assertEquals("github.com", remoteHost("https://github.com/team/app.git"))
    }

    private fun store(cipher: SecretCipher = XorCipher(0x33)) =
        EncryptedGitCredentialStore(FileSystem.SYSTEM, file.toOkioPath(), cipher, Dispatchers.Unconfined)

    /** A stand-in for the Keystore cipher; a different key garbles the JSON, which is what decryption failure does. */
    private class XorCipher(private val key: Int) : SecretCipher {
        override fun encrypt(plaintext: ByteArray) =
            ByteArray(plaintext.size) { (plaintext[it].toInt() xor key).toByte() }
        override fun decrypt(ciphertext: ByteArray) = encrypt(ciphertext)
    }
}

class LocalEditorReducerTest {
    @Test
    fun anEditMakesTheOldTextUndoableAndEndsTheRedoHistory() {
        val editor = LocalEditorState(text = "one", savedText = "one", loading = false, redoStack = listOf("x"))

        val edited = editor.edited("two")!!

        assertEquals("two", edited.text)
        assertTrue(edited.isDirty && edited.canUndo && !edited.canRedo)
    }

    @Test
    fun undoAndRedoWalkTheHistory() {
        val edited = LocalEditorState(text = "one", savedText = "one", loading = false).edited("two")!!

        val undone = edited.undone()!!
        assertEquals("one", undone.text)
        assertTrue(!undone.isDirty && undone.canRedo)

        assertEquals("two", undone.redone()!!.text)
    }

    @Test
    fun nothingChangesWhileLoadingOrWithoutHistory() {
        assertNull(LocalEditorState(loading = true).edited("text"))
        assertNull(LocalEditorState(loading = false).undone())
        assertNull(LocalEditorState(loading = false).redone())
    }

    @Test
    fun closingATabSelectsItsNeighbour() {
        val state = LocalWorkspaceState(slug = "demo", openedFiles = listOf("a", "b", "c"), selectedTab = 2)

        val closed = reduce(state, LocalWorkspaceMsg.FileClosed("c"))

        assertEquals(listOf("a", "b"), closed.openedFiles)
        assertEquals("b", closed.selectedFile)
    }
}
