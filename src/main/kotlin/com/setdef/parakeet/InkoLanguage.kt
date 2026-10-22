package com.setdef.parakeet

import com.intellij.lang.Language
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

object InkoLanguage : Language("Inko") {
    private fun readResolve(): Any = InkoLanguage
}

object InkoIcons {
    @JvmField
    val FILE: Icon = IconLoader.getIcon("/icons/inko.svg", InkoIcons::class.java)
}

class InkoFileType private constructor() : LanguageFileType(InkoLanguage) {
    override fun getName(): String = "Inko"
    override fun getDescription(): String = "Inko"
    override fun getDefaultExtension(): String = Inko.EXTENSION
    override fun getIcon(): Icon = InkoIcons.FILE

    companion object {
        @JvmField
        val INSTANCE = InkoFileType()
    }
}
