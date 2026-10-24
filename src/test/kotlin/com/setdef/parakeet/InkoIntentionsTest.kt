package com.setdef.parakeet


/** Intentions on ⌥⏎, with `inko doc` output supplied by a fake for the ones that resolve calls. */
class InkoIntentionsTest : InkoTestCase(onDisk = true) {
    override fun setUp() {
        super.setUp()
        // Parakeet's own reading of the code, which stands in only where Cuttlebone can't check it.
        cuttleboneCantCheckTheCode()
        // Intentions are only offered in project content; on-disk test files aren't in the light project's content root.
        putTempDirInProjectContent()
        myFixture.addFileToProject("inko.pkg", "")
        myFixture.addFileToProject("src/app/util.inko", "fn pub resize(width: Int, height: Int) -> Int {\n  width * height\n}\n")
        useFakeInko(FakeInkoDoc.cli { mirror ->
            val util = mirror.resolve("src/app/util.inko")
            listOf(
                FakeInkoDoc.module("app.util", util, methods = listOf(
                    FakeInkoDoc.member("resize", util, 1, 1, signature = "fn pub resize(width: Int, height: Int) -> Int"),
                )),
                FakeInkoDoc.module("std.int", mirror.resolve("std/int.inko"), types = listOf(FakeInkoDoc.type("Int", 1))),
            )
        })
    }

    /** Applies the intention titled [title] at `<caret>` and returns the text; null if it isn't offered. */
    private fun apply(text: String, title: String): String? {
        val file = myFixture.addFileToProject("src/main.inko", text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(text.indexOf("<caret>"))
        val intention = myFixture.availableIntentions.firstOrNull { it.text == title } ?: return null
        myFixture.launchAction(intention)
        return myFixture.editor.document.text
    }

    private fun body(code: String) = "import app.util (resize)\n\nfn main(a: Int, b: Int) {\n$code\n}\n"

    // Flip.

    fun testFlipsEquality() = assertEquals(body("  b == a"), apply(body("  a <caret>== b"), "Flip '=='"))

    fun testFlipsComparisonDirection() = assertEquals(body("  b > a"), apply(body("  a <caret>< b"), "Flip '<' to '>'"))

    fun testFlipKeepsTheLeftSideGroupedWithParentheses() =
        assertEquals(body("  b == (a + 1)"), apply(body("  a + 1 <caret>== b"), "Flip '=='"))

    fun testFlipsAndAroundComparisons() =
        assertEquals(body("  b > 2 and a == 1"), apply(body("  a == 1 <caret>and b > 2"), "Flip 'and'"))

    fun testDoesNotFlipArithmetic() = assertNull(apply(body("  a <caret>+ b"), "Flip '+'"))

    // Invert if.

    fun testInvertsIfAndSwapsBlocks() = assertEquals(
        body("  if a != 1 {\n    b\n  } else {\n    a\n  }"),
        apply(body("  <caret>if a == 1 {\n    a\n  } else {\n    b\n  }"), "Invert 'if' condition"),
    )

    fun testInvertsWithNotAroundCompoundConditions() = assertEquals(
        body("  if !(a == 1 and b == 2) {\n    b\n  } else {\n    a\n  }"),
        apply(body("  <caret>if a == 1 and b == 2 {\n    a\n  } else {\n    b\n  }"), "Invert 'if' condition"),
    )

    fun testInvertingANegationDropsIt() = assertEquals(
        body("  let c = true\n  if c {\n    b\n  } else {\n    a\n  }"),
        apply(body("  let c = true\n  <caret>if !c {\n    a\n  } else {\n    b\n  }"), "Invert 'if' condition"),
    )

    fun testNotOfferedWithoutElse() = assertNull(apply(body("  <caret>if a == 1 {\n    a\n  }"), "Invert 'if' condition"))

    // if ⇄ match.

    fun testIfChainBecomesMatch() = assertEquals(
        body("  match a {\n    case 1 or 2 -> b\n    case 3 -> {\n      let c = b\n      c\n    }\n    case _ -> a\n  }"),
        apply(body("  <caret>if a == 1 or a == 2 {\n    b\n  } else if a == 3 {\n    let c = b\n    c\n  } else {\n    a\n  }"), "Replace 'if' with 'match'"),
    )

    fun testMatchWithoutElseGetsACatchAll() = assertEquals(
        body("  match a {\n    case 1 -> b\n    case _ -> {}\n  }"),
        apply(body("  <caret>if a == 1 {\n    b\n  }"), "Replace 'if' with 'match'"),
    )

    fun testNoMatchWhenSubjectsDifferOrAreCalls() {
        assertNull(apply(body("  <caret>if a == 1 {\n    b\n  } else if b == 2 {\n    a\n  }"), "Replace 'if' with 'match'"))
        assertNull(apply(body("  <caret>if resize(a, b) == 1 {\n    b\n  }"), "Replace 'if' with 'match'"))
    }

    fun testMatchBecomesIfChain() = assertEquals(
        body("  if a == 1 or a == 2 {\n    b\n  } else if a == 3 {\n    let c = b\n    c\n  } else {\n    a\n  }"),
        apply(body("  <caret>match a {\n    case 1 or 2 -> b\n    case 3 -> {\n      let c = b\n      c\n    }\n    case _ -> a\n  }"), "Replace 'match' with 'if'"),
    )

    fun testNoIfForBindingsOrGuards() {
        assertNull(apply(body("  <caret>match a {\n    case n if n > 1 -> b\n    case _ -> a\n  }"), "Replace 'match' with 'if'"))
        assertNull(apply(body("  <caret>match a {\n    case n -> n\n  }"), "Replace 'match' with 'if'"))
    }

    // Argument names.

    fun testAddsArgumentNames() =
        assertEquals(body("  resize(width: 10, height: 20)"), apply(body("  resize(<caret>10, 20)"), "Add argument names"))

    fun testLeavesLeadingSameNamedArgumentsPositional() =
        assertEquals(body("  let width = 1\n  resize(width, height: 20)"), apply(body("  let width = 1\n  resize(width, <caret>20)"), "Add argument names"))

    fun testRemovesArgumentNamesInOrder() =
        assertEquals(body("  resize(10, 20)"), apply(body("  resize(width: 10, <caret>height: 20)"), "Remove argument names"))

    fun testDoesNotRemoveNamesOutOfOrder() =
        assertNull(apply(body("  resize(height: 20, <caret>width: 10)"), "Remove argument names"))

    // let types.

    fun testSpecifiesAKnownType() = assertEquals(body("  let count: Int = 0"), apply(body("  let <caret>count = 0"), "Specify type explicitly"))

    fun testSpecifiesACallsReturnType() =
        assertEquals(body("  let area: Int = resize(1, 2)"), apply(body("  let <caret>area = resize(1, 2)"), "Specify type explicitly"))

    fun testNoTypeWhenUnknown() = assertNull(apply(body("  let <caret>x = a.unknown"), "Specify type explicitly"))

    fun testRemovesAMatchingType() = assertEquals(body("  let count = 0"), apply(body("  let <caret>count: Int = 0"), "Remove explicit type"))

    fun testKeepsATypeTheValueDoesNotShow() = assertNull(apply(body("  let <caret>items: Array[Int] = []"), "Remove explicit type"))
}
