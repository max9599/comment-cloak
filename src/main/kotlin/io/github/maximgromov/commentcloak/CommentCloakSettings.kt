package io.github.maximgromov.commentcloak

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

/** How a hidden comment block is rendered in the editor. */
enum class MarkerStyle {
    /** Compact rounded pill with the CommentCloak icon and a line count, drawn in place of the block. */
    PILL,

    /** The stock fold placeholder text (see [CommentCloakSettings.placeholder]). */
    PLAIN
}

/**
 * Application-level persistent settings for the CommentCloak plugin.
 *
 * The service instance *is* its own state holder, so fields can be read and written directly.
 */
@Service(Service.Level.APP)
@State(name = "CommentCloakSettings", storages = [Storage("commentCloak.xml")])
class CommentCloakSettings : PersistentStateComponent<CommentCloakSettings> {

    /** Master toggle. Persisted across restarts. */
    var enabled: Boolean = false

    var hideLineComments: Boolean = true
    var hideBlockComments: Boolean = true
    var hideDocComments: Boolean = true
    var hideHtmlComments: Boolean = true

    /** Only hide comments (or merged comment runs) whose text is at least this long. 0 = no limit. */
    var minLength: Int = 0

    /** Collapse the whole comment line(s), including the preceding line break and indentation. */
    var foldWholeLines: Boolean = true

    /** Text shown in place of a hidden comment (used by [MarkerStyle.PLAIN] and trailing comments). */
    var placeholder: String = DEFAULT_PLACEHOLDER

    /** How whole-line comment blocks are rendered while hidden. */
    var markerStyle: MarkerStyle = MarkerStyle.PILL

    /** Regexes; a comment matching any of them (via [Regex.containsMatchIn]) is never hidden. */
    var keepPatterns: MutableList<String> = defaultKeepPatterns()

    /**
     * When a multi-line block/doc/HTML comment matches a keep pattern, hide the paragraphs that do
     * not match instead of leaving the whole comment visible.
     */
    var partialKeep: Boolean = true

    private var cachedSource: List<String>? = null
    private var cachedRegexes: List<Regex> = emptyList()

    /** Compiled [keepPatterns]; invalid patterns are silently skipped. */
    @Synchronized
    fun keepRegexes(): List<Regex> {
        val source = keepPatterns.toList()
        if (cachedSource == source) return cachedRegexes
        cachedRegexes = source.mapNotNull { pattern ->
            try {
                if (pattern.isBlank()) null else Regex(pattern)
            } catch (_: Exception) {
                null
            }
        }
        cachedSource = source
        return cachedRegexes
    }

    @Synchronized
    fun invalidateCache() {
        cachedSource = null
        cachedRegexes = emptyList()
    }

    fun effectivePlaceholder(): String = placeholder.ifBlank { DEFAULT_PLACEHOLDER }

    override fun getState(): CommentCloakSettings = this

    override fun loadState(state: CommentCloakSettings) {
        XmlSerializerUtil.copyBean(state, this)
        invalidateCache()
    }

    companion object {
        const val DEFAULT_PLACEHOLDER: String = "⋯"

        @JvmStatic
        fun getInstance(): CommentCloakSettings =
            ApplicationManager.getApplication().getService(CommentCloakSettings::class.java)

        @JvmStatic
        fun defaultKeepPatterns(): MutableList<String> = mutableListOf(
            """\b(TODO|FIXME|XXX|HACK|BUG|NOTE)\b""",
            """^\s*(//|/\*|#|<!--)\s*(eslint|@ts-|prettier-|biome-|istanbul|webpack|c8 |v8 |@vite|@jsx|@flow|@license|@preserve|@format|noinspection|#?region\b|#?endregion\b|/// <reference|Copyright|SPDX-License|sourceMappingURL|pragma|type: ?ignore|noqa|@ts-check|@__PURE__)"""
        )
    }
}
