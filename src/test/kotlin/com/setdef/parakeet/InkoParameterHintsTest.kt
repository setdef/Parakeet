package com.setdef.parakeet

/** Parameter-name and type hints' providers. What they show is Cuttlebone's (CuttleboneFeaturesTest). */
class InkoParameterHintsTest : InkoTestCase() {
    fun testTheProviderIsRegisteredForInko() {
        myFixture.configureByText("main.inko", "")
        assertNotNull(InkoParameterHintsProvider().createCollector(myFixture.file, myFixture.editor))
        assertNotNull(InkoTypeHintsProvider().createCollector(myFixture.file, myFixture.editor))
        val providers = com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory.getProvidersForLanguage(InkoLanguage)
        assertTrue(providers.map { it.providerId }.toString(), providers.map { it.providerId }.containsAll(listOf("parakeet.inko.parameters", "parakeet.inko.types")))
    }
}
