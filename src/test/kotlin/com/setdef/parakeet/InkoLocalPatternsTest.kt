package com.setdef.parakeet

import com.intellij.psi.util.PsiTreeUtil

/**
 * What binds a local, from Inko's grammar (ast/src/parser.rs: `pattern`,
 * `define_variable`, `for_expression`, `match_case`, `define_constructor`).
 * AUDIT.md findings 1.2 and 1.3, and section 3's module-qualified constant patterns.
 */
class InkoLocalPatternsTest : InkoTestCase() {
    private fun visible(text: String, marker: String = "<here>"): Set<String> =
        InkoLocals.visibleAt(text.replace(marker, ""), text.indexOf(marker)).map { it.name }.toSet()

    private fun declared(text: String): List<String> = InkoLocals.declarations(text).map { it.name }

    private val total = """
        |fn total(items: Array[Int]) -> Int {
        |  let mut sum = 0
        |  for (index, value) in items.iter.with_index { sum += value + index<in-loop> }
        |  let Some(first) = items.first else return 0
        |  let (a, (b, c)) = (1, (2, 3))
        |  sum + first + a + b + c<after>
        |}
        |""".trimMargin()

    fun testForLoopVariablesAreLocalsOfTheLoop() {
        assertEquals(setOf("items", "sum", "index", "value"), visible(total.replace("<after>", ""), "<in-loop>"))
    }

    fun testLetPatternsBindEveryName() {
        assertEquals(setOf("items", "sum", "first", "a", "b", "c"), visible(total.replace("<in-loop>", ""), "<after>"))
    }

    fun testEveryKindOfPatternBindsItsNames() {
        val text = "fn f(p: Person, xs: Array[Int]) {\n" +
            "  let { @name = name, @age = mut age } = p\n" +
            "  let [first, _, third] = xs else return\n" +
            "  let Ok(x: Int) or Error(x: Int) = r else return\n" +
            "  <here>\n}\n"
        assertEquals(setOf("p", "xs", "name", "age", "first", "third", "x"), visible(text))
    }

    // `mod.CONST` is a constant pattern: `mod` is a module, not a binding.
    fun testModuleQualifiedConstantPatternsBindNothing() {
        val text = "import std.int\n\nfn f(v: Int) -> Int {\n  match v {\n    case int.MAX -> 1\n    case n -> <here>n\n  }\n}\n"
        assertEquals(setOf("v", "n"), visible(text))
        assertFalse("int" in declared(text.replace("<here>", "")))
    }

    fun testEachMatchArmHasItsOwnBindings() {
        val text = "fn f(o: Option[Int]) -> Int {\n  match o {\n    case Some(a) if a > 1 -> a\n    case Some(b) -> <here>b\n    case None -> 0\n  }\n}\n"
        assertEquals(setOf("o", "b"), visible(text))
    }

    private val shape = """
        |type pub enum Shape {
        |  case Circle(Float)
        |  case Square(Float)
        |
        |  fn pub area -> Float {
        |    0.0
        |  }
        |
        |  fn pub kind -> Int {
        |    match self {
        |      case Circle(r) -> 1
        |      case Square(_) -> 2
        |    }
        |  }
        |}
        |""".trimMargin()

    // Constructor definitions bind nothing, and don't swallow what follows them.
    fun testEnumConstructorsAreNotBindings() {
        assertEquals(listOf("r"), declared(shape))
    }

    fun testAMethodAfterEnumConstructorsIsNotALocal() {
        myFixture.configureByText("shape.inko", shape)
        val area = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(shape.indexOf("area")), InkoDeclaration::class.java, false)!!
        assertFalse(area.isLocal)
        assertEquals("method", InkoFindUsagesProvider().getType(area))
    }

    fun testLoopVariablesAreDeclarationsWithUsages() {
        myFixture.configureByText("main.inko", total.replace("<in-loop>", "").replace("<after>", ""))
        val text = myFixture.file.text
        val value = PsiTreeUtil.getParentOfType(myFixture.file.findElementAt(text.indexOf("value)")), InkoDeclaration::class.java, false)!!
        assertTrue(value.isLocal)
        assertEquals(listOf(text.indexOf("value +")), InkoUsages.inFile(value).filter { !it.second }.map { it.first.textRange.startOffset })
    }
}
