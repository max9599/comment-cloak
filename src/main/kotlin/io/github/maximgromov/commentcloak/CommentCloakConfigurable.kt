package io.github.maximgromov.commentcloak

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import javax.swing.JTextArea

/** Application settings page: Settings | Editor | CommentCloak. */
class CommentCloakConfigurable : BoundConfigurable("CommentCloak") {

    private val settings: CommentCloakSettings get() = CommentCloakSettings.getInstance()

    private var keepPatternsText: String = CommentCloakSettings.getInstance().keepPatterns.joinToString("\n")

    override fun createPanel(): DialogPanel = panel {
        group("What to hide") {
            row {
                checkBox("Line comments (//, #, --)").bindSelected(settings::hideLineComments)
            }
            row {
                checkBox("Block comments (/* ... */)").bindSelected(settings::hideBlockComments)
            }
            row {
                checkBox("Documentation comments (/** ... */)").bindSelected(settings::hideDocComments)
            }
            row {
                checkBox("HTML/XML comments (<!-- ... -->)").bindSelected(settings::hideHtmlComments)
            }
            row {
                comment("Comments are only hidden visually; files are never modified.")
            }
        }

        group("How to hide") {
            buttonsGroup("Hidden-comment marker:") {
                row {
                    radioButton("Compact pill", MarkerStyle.PILL)
                        .comment("A small rounded badge with the line count, drawn over the comment's own lines.")
                }
                row {
                    radioButton("Plain fold placeholder", MarkerStyle.PLAIN)
                        .comment("The stock fold marker at the end of the previous line.")
                }
            }.bind(settings::markerStyle)
            row {
                checkBox("Collapse whole comment lines").bindSelected(settings::foldWholeLines)
                    .comment("Plain marker only: also swallows the line break and indentation.")
            }
            row("Only hide comments with at least:") {
                intTextField(0..100_000).bindIntText(settings::minLength).columns(6)
                label("characters (0 = no limit)")
            }
            row("Placeholder:") {
                textField().bindText(settings::placeholder).columns(8)
            }
        }

        group("Always keep comments matching (regex, one per line)") {
            row {
                checkBox("In a hidden block, show only the paragraph that matches a keep pattern")
                    .bindSelected(settings::partialKeep)
                    .comment("A long comment with one TODO keeps the TODO paragraph and hides the rest.")
            }
            row {
                textArea()
                    .rows(6)
                    .align(AlignX.FILL)
                    .bindText(::keepPatternsText)
                    .validationOnApply { field -> validatePatterns(field) }
            }
        }
    }

    private fun validatePatterns(field: JTextArea): ValidationInfo? {
        val bad = field.text.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .filter { pattern ->
                try {
                    Regex(pattern); false
                } catch (_: Exception) {
                    true
                }
            }
        if (bad.isEmpty()) return null
        return ValidationInfo("Invalid regular expression: ${bad.first()}", field)
    }

    override fun reset() {
        keepPatternsText = settings.keepPatterns.joinToString("\n")
        super.reset()
    }

    override fun apply() {
        super.apply()
        settings.keepPatterns = keepPatternsText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toMutableList()
        settings.invalidateCache()
        for (project in ProjectManager.getInstance().openProjects) {
            if (!project.isDisposed) project.service<CommentCloakManager>().refresh()
        }
    }

    override fun isModified(): Boolean =
        super.isModified() || keepPatternsText.lines().map { it.trim() }.filter { it.isNotEmpty() } !=
            settings.keepPatterns
}
