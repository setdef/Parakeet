package com.setdef.parakeet

import com.intellij.codeInsight.template.TemplateActionContext
import com.intellij.codeInsight.template.TemplateContextType

/** Where Inko live templates apply: anywhere in an Inko file except comments and strings. */
class InkoTemplateContextType : TemplateContextType("Inko") {
    override fun isInContext(context: TemplateActionContext): Boolean {
        val file = context.file as? InkoFile ?: return false
        val leaf = file.findElementAt(maxOf(context.startOffset - 1, 0)) ?: return true
        return !leaf.isToken(InkoTokens.COMMENT) && leaf.node.elementType !in InkoTokens.STRINGS
    }
}
