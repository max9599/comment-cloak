package io.github.maximgromov.commentcloak

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.xmlb.XmlSerializerUtil

/** Snapshots the application-level settings so tests cannot leak state into each other. */
abstract class CommentCloakTestBase : BasePlatformTestCase() {

    protected val settings: CommentCloakSettings get() = CommentCloakSettings.getInstance()

    private val backup = CommentCloakSettings()

    override fun setUp() {
        super.setUp()
        XmlSerializerUtil.copyBean(settings, backup)
        backup.keepPatterns = ArrayList(settings.keepPatterns)
        resetToDefaults()
    }

    override fun tearDown() {
        try {
            settings.loadState(backup)
            settings.keepPatterns = ArrayList(backup.keepPatterns)
            settings.invalidateCache()
        } catch (e: Throwable) {
            addSuppressedException(e)
        } finally {
            super.tearDown()
        }
    }

    private fun resetToDefaults() {
        settings.enabled = false
        settings.hideLineComments = true
        settings.hideBlockComments = true
        settings.hideDocComments = true
        settings.hideHtmlComments = true
        settings.minLength = 0
        settings.foldWholeLines = true
        settings.placeholder = CommentCloakSettings.DEFAULT_PLACEHOLDER
        settings.markerStyle = MarkerStyle.PILL
        settings.partialKeep = true
        settings.keepPatterns = CommentCloakSettings.defaultKeepPatterns()
        settings.invalidateCache()
    }
}
