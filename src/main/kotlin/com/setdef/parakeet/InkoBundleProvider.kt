package com.setdef.parakeet

import com.intellij.openapi.extensions.PluginAware
import com.intellij.openapi.extensions.PluginDescriptor
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider
import org.jetbrains.plugins.textmate.api.TextMateBundleProvider.PluginBundle

/**
 * Registers the Inko TextMate bundle (grammar and language configuration from
 * inko-lang/vscode), shipped on disk in the plugin's `bundles/inko` directory.
 */
class InkoBundleProvider : TextMateBundleProvider, PluginAware {
    private var plugin: PluginDescriptor? = null

    override fun setPluginDescriptor(pluginDescriptor: PluginDescriptor) {
        plugin = pluginDescriptor
    }

    override fun getBundles(): List<PluginBundle> {
        val path = plugin?.pluginPath ?: return emptyList()
        return listOf(PluginBundle("Inko", path.resolve("bundles/inko")))
    }
}
