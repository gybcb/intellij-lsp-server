package com.ruin.lsp.util

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.impl.DocumentImpl
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.FileDocumentManagerImpl
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.util.Computable
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.InvalidDataException
import com.intellij.openapi.util.Ref
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.WindowManager
import com.intellij.openapi.wm.impl.WindowManagerImpl
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiManager
import com.intellij.psi.util.PsiUtilCore
import com.ruin.lsp.model.MyLanguageClient
import com.ruin.lsp.model.MyLanguageServer
import org.eclipse.lsp4j.Position
import org.jdom.JDOMException
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.URLDecoder
import java.nio.file.Paths
import java.util.*

private val LOG = Logger.getInstance("#com.ruin.lsp.util.ProjectUtil")

fun ensurePsiFromUri(uri: String) = resolvePsiFromUri(uri)
    ?: throw IllegalArgumentException("Unable to resolve document and file at $uri")

fun resolvePsiFromUri(uri: String): Pair<Project, PsiFile>? {
    val (project, filePath) = resolveProjectFromUri(uri) ?: return null
    val file = getPsiFile(project, filePath) ?: return null
    return Pair(project, file)
}

fun ensureProjectFromUri(uri: String) = resolveProjectFromUri(uri)
    ?: throw IllegalArgumentException("Unable to resolve document and file at $uri")

fun resolveProjectFromUri(uri: String): Pair<Project, String>? {
    // TODO: in-memory virtual files for testing have temp:/// prefix, figure out how to resolve the document from them
    // otherwise it gets confusing to have to look up the line and column being tested in the test document
    val newUri = normalizeUri(uri)
    val topFile = File(URI(newUri))
    var directory = topFile.parentFile
    while (directory != null) {
        val imlFile = directory.listFiles().firstOrNull { it.extension == "iml" }
        if (imlFile != null) {
            if(isRootProject(imlFile)) {
                val proj = ensureProject(imlFile.absolutePath)
                val projPathUri = getURIForFile(File(proj.basePath))
                val prefix = newUri.commonPrefixWith(projPathUri, true)
                assert(prefix.isNotEmpty())
                val filePathFromRoot = newUri.substring(prefix.length)
                return Pair(proj, filePathFromRoot)
            }
        }
        directory = directory.parentFile
    }

    LOG.warn("Unable to resolve document from URI $newUri")
    return null
}

data class CachedProject(val project: Project, var disposable: Disposable? = null)

val sProjectCache = HashMap<String, CachedProject>()

internal class DumbModeNotifier(private val client: MyLanguageClient?,
                                private val server: MyLanguageServer?) : DumbService.DumbModeListener {
    override fun enteredDumbMode() {
        client?.notifyIndexStarted()
    }

    override fun exitDumbMode() {
        client?.notifyIndexFinished()
        server?.computeAllDiagnostics()
    }
}

fun registerIndexNotifier(project: Project, client: MyLanguageClient, server: MyLanguageServer) {
    val cached = sProjectCache.values.find { it.project == project } ?: return
    if (cached.disposable != null) {
        return
    }
    cached.disposable = Disposer.newDisposable()
    project.messageBus.connect(cached.disposable!!).subscribe(DumbService.DUMB_MODE, DumbModeNotifier(client, server))

    if (DumbService.isDumb(project)) {
        client.notifyIndexStarted()
    }
}

fun cacheProject(absolutePath: String, project: Project) {
    LOG.info("Caching project that was found at $absolutePath.")
    if (sProjectCache.containsKey(absolutePath)) {
        sProjectCache[absolutePath]!!.disposable?.dispose()
    }
    sProjectCache[absolutePath] = CachedProject(project)
}

fun ensureProject(projectPath: String): Project {
    val project = getProject(projectPath)
        ?: throw IllegalArgumentException("Couldn't find document at " + projectPath)
    if (project.isDisposed)
        throw IllegalArgumentException("Project $project was already disposed!")

    return project
}

fun getProject(projectPath: String): Project? {
    val mgr = ProjectManagerEx.getInstanceEx()

    val cached = sProjectCache[projectPath]
    if (cached != null) {
        if (!cached.project.isDisposed) {
            return cached.project
        } else {
            LOG.info("Cached document at $projectPath was disposed, reopening.")
        }
    }

    try {
        if (!File(projectPath).exists()) {
            LOG.warn("Project at $projectPath doesn't exist.")
            return null
        }

        val projectRef = Ref<Project>()
        ApplicationManager.getApplication().runWriteAction {
            try {
                val alreadyOpenProject = mgr.openProjects.find { proj ->
                    if (proj.projectFilePath?.contains(".idea") == true) {
                        val prefix = proj.projectFilePath!!
                            .substringBefore(".idea")
                            .replace("\\", "/")
                        projectPath
                            .replace("\\", "/")
                            .startsWith(prefix, true)
                    } else {
                        false
                    }
                }

                val project = alreadyOpenProject ?: mgr.loadAndOpenProject(projectPath)
                projectRef.set(project)

                hideProjectFrame(project)
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: JDOMException) {
                e.printStackTrace()
            } catch (e: InvalidDataException) {
                e.printStackTrace()
            }
        }

        val project = projectRef.get() ?: throw IOException("Failed to obtain document " + projectPath)

        // Wait until the project is initialized to prevent invokeAndWait hangs
        while (!project.isInitialized) {
            Thread.sleep(1000)
        }

        cacheProject(projectPath, project)
        return project
    } catch (e: IOException) {
        e.printStackTrace()
    }

    LOG.warn("Exception occurred trying to find document for path $projectPath")
    return null
}

fun getPsiFile(project: Project, filePath: String): PsiFile? {
    return getPsiFile(project, getVirtualFile(project, filePath))
}

fun getPsiFile(project: Project, virtual: VirtualFile): PsiFile? {
    return invokeAndWaitIfNeeded(asWriteAction(
        Computable<PsiFile> {
            val mgr = PsiManager.getInstance(project)
            val file = mgr.findFile(virtual)

            if (file == null) {
                LOG.warn("Unable to find PSI file for virtual file ${virtual.name}")
                return@Computable null
            }

            PsiUtilCore.ensureValid(file)
            file
        }))
}

fun getVirtualFile(project: Project, filePath: String): VirtualFile {
    val file = File(project.basePath, filePath)
    if (!file.exists()) {
        throw IllegalArgumentException("Couldn't find file " + file)
    }

    // load the VirtualFile and ensure it's up to date
    val virtual = LocalFileSystem.getInstance()
        .refreshAndFindFileByIoFile(file)
    if (virtual == null || !virtual.exists()) {
        throw IllegalArgumentException("Couldn't locate virtual file @" + file)
    }
    LocalFileSystem.getInstance().refreshFiles(Collections.singletonList(virtual))

    return virtual
}

fun getDocument(uri: String): Document? {
    val normalizedUri = normalizeUri(uri)
    val (project, filePath) = resolveProjectFromUri(normalizedUri) ?: return null
    val virtual = getVirtualFile(project, filePath)
    return invokeAndWaitIfNeeded(asWriteAction(
        Computable<Document> {
            val mgr = PsiManager.getInstance(project)
            val file = mgr.findFile(virtual)

            if (file == null) {
                LOG.warn("Unable to find PSI file for virtual file ${virtual.name}")
                return@Computable null
            }

            val doc = getDocument(file) ?: return@Computable null
            doc
        }))
}

fun getDocument(file: PsiFile): Document? {
    val virtual = file.virtualFile ?: return file.viewProvider.document
    var doc = FileDocumentManager.getInstance()
        .getDocument(virtual)
    if (doc == null) {
        FileDocumentManagerImpl.registerDocument(
            DocumentImpl(file.viewProvider.contents),
            virtual)
        doc = FileDocumentManager.getInstance()
            .getDocument(virtual)

        if (doc == null) {
            LOG.warn("Unable to find Document for virtual file ${virtual.name}")
            return null
        }
    }

    return doc
}

fun reloadDocument(doc: Document, project: Project) {
    FileDocumentManager.getInstance().reloadFromDisk(doc)
    PsiDocumentManager.getInstance(project).commitDocument(doc)
}

fun createEditor(context: Disposable, file: PsiFile, position: Position = Position(0, 0)): EditorEx {
    val doc = getDocument(file)!!
    val editorFactory = EditorFactory.getInstance()
    val created = editorFactory.createEditor(doc, file.project) as EditorEx
    created.caretModel.moveToLogicalPosition(LogicalPosition(position.line, position.character))

    Disposer.register(context, Disposable { editorFactory.releaseEditor(created) })

    return created
}

/**
 * Gets a Windows-compatible URI from a VirtualFile.
 * The getPath() method of VirtualFile is missing an extra slash in the "file:///" protocol.
 */
fun getURIForFile(file: VirtualFile) = normalizeUri(file.url)

fun getURIForFile(file: PsiFile) = getURIForFile(file.virtualFile)

fun getURIForFile(file: File) = normalizeUri(file.toURI().toURL().toString())


/**
 * Converts URIs to have forward slashes and ensures the protocol has three slashes.
 *
 * Important for testing URIs for equality across platforms.
 */
fun normalizeUri(uri: String): String {
    val protocolRegex = "^file:/+".toRegex()
    val trailingSlashRegex = "/$".toRegex()
    var decodedUri = URLDecoder.decode(uri, "UTF-8")
    decodedUri = trailingSlashRegex.replace(decodedUri, "")
    decodedUri = protocolRegex.replace(decodedUri, "file:///")
    return decodedUri.replace("\\", "/")
}

fun uriToPath(uri: String): String {
    val newUri = normalizeUri(URLDecoder.decode(uri, "UTF-8"))

    val isWindowsPath = """^file:/+\w:""".toRegex().containsMatchIn(newUri)

    return if (isWindowsPath)
        Paths.get("^file:/+".toRegex().replace(newUri, ""))
            .toString().replace("\\", "/")
    else {
        "^file:/+".toRegex().replace(newUri, "/")
    }
}

private fun hideProjectFrame(project: Project?) {
    val mgr = WindowManager.getInstance()
    val existing = mgr.getFrame(project)
    if (null != existing) {
        // hide any existing frames. We may want this
        //  to be a preference... Not sure
        existing.isVisible = false
        return // already done
    }

    if (mgr !is WindowManagerImpl) {
        // unit test?
        return
    }

    val impl = mgr.allocateFrame(project!!)
    impl.isVisible = false
}

fun toggleProjectFrame(project: Project) {
    val mgr = WindowManager.getInstance()
    val existing = mgr.getFrame(project)
    if (null != existing) {
        existing.isVisible = !existing.isVisible
    }
}
