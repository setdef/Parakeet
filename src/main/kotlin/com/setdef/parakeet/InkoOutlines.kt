package com.setdef.parakeet

import com.intellij.icons.AllIcons
import com.intellij.psi.PsiFile
import com.intellij.psi.util.CachedValueProvider
import com.intellij.psi.util.CachedValuesManager
import javax.swing.Icon

/** The file's outline, recomputed when it changes. */
val PsiFile.inkoOutline: InkoOutline
    get() = CachedValuesManager.getCachedValue(this) { CachedValueProvider.Result.create(InkoOutline.of(text), this) }

val InkoOutline.Kind.icon: Icon
    get() = when (this) {
        InkoOutline.Kind.TYPE -> AllIcons.Nodes.Class
        InkoOutline.Kind.TRAIT -> AllIcons.Nodes.Interface
        InkoOutline.Kind.IMPL -> AllIcons.Nodes.AbstractClass
        InkoOutline.Kind.METHOD -> AllIcons.Nodes.Method
        InkoOutline.Kind.FIELD -> AllIcons.Nodes.Field
        InkoOutline.Kind.CONSTANT -> AllIcons.Nodes.Constant
    }
