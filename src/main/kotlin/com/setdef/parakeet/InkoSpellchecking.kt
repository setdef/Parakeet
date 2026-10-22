package com.setdef.parakeet

import com.intellij.psi.PsiElement
import com.intellij.spellchecker.tokenizer.SpellcheckingStrategy
import com.intellij.spellchecker.tokenizer.Tokenizer

/** Spell-checks comments, and strings without `${…}` interpolation (whose code would be flagged). */
class InkoSpellcheckingStrategy : SpellcheckingStrategy() {
    override fun getTokenizer(element: PsiElement): Tokenizer<*> = when {
        element.isToken(InkoTokens.COMMENT) -> TEXT_TOKENIZER
        element.isToken(InkoTokens.STRING) && !element.text.contains("\${") -> TEXT_TOKENIZER
        else -> EMPTY_TOKENIZER
    }
}
