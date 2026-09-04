package io.github.maximgromov.commentcloak

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager

/**
 * Per-project orchestration of the CommentCloak feature.
 *
 * The on/off state itself is application wide (it lives in [CommentCloakSettings]); flipping it
 * refreshes every open project.
 */
@Service(Service.Level.PROJECT)
class CommentCloakManager(private val project: Project) {

    private val settings: CommentCloakSettings get() = CommentCloakSettings.getInstance()

    val isEnabled: Boolean get() = settings.enabled

    fun toggle() = setEnabled(!settings.enabled)

    fun setEnabled(value: Boolean) {
        settings.enabled = value
        for (openProject in openProjects()) {
            for (editor in editorsOf(openProject)) {
                CommentFolder.clearRevealed(editor)
            }
            openProject.service<CommentCloakManager>().applyToAllEditors()
        }
        ApplicationManager.getApplication().messageBus
            .syncPublisher(CommentCloakListener.TOPIC)
            .stateChanged(value)
    }

    /** Re-applies the current settings everywhere; used by the settings page's "Apply". */
    fun refresh() {
        settings.invalidateCache()
        applyToAllEditors()
    }

    fun applyToAllEditors() {
        for (editor in editorsOf(project)) applyTo(editor)
    }

    fun applyTo(editor: Editor) {
        if (project.isDisposed || editor.isDisposed) return
        if (settings.enabled) {
            CommentFolder.install(editor, project)
            CommentFolder.apply(editor, project)
        } else {
            CommentFolder.clear(editor, project)
        }
    }

    private fun openProjects(): List<Project> =
        ProjectManager.getInstance().openProjects.filter { !it.isDisposed }

    private fun editorsOf(target: Project): List<Editor> {
        val fileDocumentManager = FileDocumentManager.getInstance()
        return EditorFactory.getInstance().allEditors.filter { editor ->
            editor.project === target &&
                CommentFolder.isSupported(editor) &&
                fileDocumentManager.getFile(editor.document) != null
        }
    }

    companion object {
        @JvmStatic
        fun getInstance(project: Project): CommentCloakManager = project.service()
    }
}
