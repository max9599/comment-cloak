package io.github.maximgromov.commentcloak

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Belt-and-braces companion to [CommentCloakEditorFactoryListener]: the IDE builds its own initial
 * foldings right after a file is opened, which can drop foreign regions, so re-apply once more here.
 */
class CommentCloakFileEditorListener(private val project: Project) : FileEditorManagerListener {

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        if (!CommentCloakSettings.getInstance().enabled) return
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            val manager = CommentCloakManager.getInstance(project)
            source.getEditors(file)
                .filterIsInstance<TextEditor>()
                .map { it.editor }
                .filter { CommentFolder.isSupported(it) }
                .forEach { manager.applyTo(it) }
        }, project.disposed)
    }
}
