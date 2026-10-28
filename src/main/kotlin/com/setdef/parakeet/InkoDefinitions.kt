package com.setdef.parakeet

import java.nio.file.Path

enum class InkoDefinitionKind { MODULE, TYPE, TRAIT, CONSTANT, MODULE_METHOD, STATIC_METHOD, INSTANCE_METHOD, CONSTRUCTOR, FIELD }

/**
 * A definition in an Inko module. [owner] is the type or trait it belongs
 * to, if any; [signature] is its declaration (e.g. `fn pub value -> Int`), and
 * [documentation] its doc comment, in Markdown.
 */
data class InkoDefinition(
    val kind: InkoDefinitionKind,
    val name: String,
    val module: String,
    val owner: String?,
    val file: Path,
    val line: Int,
    val column: Int,
    val signature: String? = null,
    val documentation: String = "",
    /** For a trait, the types implementing it; for a type, the traits it implements. */
    val related: List<InkoTypeRef> = emptyList(),
    /** For a trait's method, whether implementations must define it (it has no default body). */
    val required: Boolean = false,
    /** Whether it's `pub`, so other modules can import or call it. */
    val public: Boolean = true,
)

/** A type or trait named by module, e.g. `Show` in `app.util`. */
data class InkoTypeRef(val module: String, val name: String)

/** Every definition in a project (and its dependencies and the standard library), by name and module. */
class InkoSymbols(definitions: List<InkoDefinition>) {
    private val byName: Map<String, List<InkoDefinition>> = definitions.groupBy { it.name }
    private val modules: Map<String, InkoDefinition> =
        definitions.filter { it.kind == InkoDefinitionKind.MODULE }.associateBy { it.module }
    private val moduleByFile: Map<Path, String> = modules.values.associate { it.file to it.module }
    private val byOwner: Map<String, List<InkoDefinition>> = definitions.filter { it.owner != null }.groupBy { it.owner!! }
    private val byModule: Map<String, List<InkoDefinition>> = definitions.groupBy { it.module }

    val moduleNames: Set<String> get() = modules.keys

    /** Members (methods, fields, constructors) of the type or trait named [owner]. */
    fun ownedBy(owner: String, vararg kinds: InkoDefinitionKind): List<InkoDefinition> =
        byOwner[owner].orEmpty().filter { kinds.isEmpty() || it.kind in kinds }

    /** Top-level definitions in [module] (types, traits, constants, module methods). */
    fun topLevelIn(module: String): List<InkoDefinition> = byModule[module].orEmpty().filter {
        it.kind in setOf(InkoDefinitionKind.TYPE, InkoDefinitionKind.TRAIT, InkoDefinitionKind.CONSTANT, InkoDefinitionKind.MODULE_METHOD)
    }

    val names: Set<String> get() = byName.keys

    /** Every definition of [kind] (e.g. every trait). */
    fun ofKind(kind: InkoDefinitionKind): List<InkoDefinition> = byName.values.flatten().filter { it.kind == kind }

    fun named(name: String, vararg kinds: InkoDefinitionKind): List<InkoDefinition> =
        byName[name].orEmpty().filter { kinds.isEmpty() || it.kind in kinds }

    fun module(name: String): InkoDefinition? = modules[name]

    fun moduleOf(file: Path): String? = moduleByFile[file]

    /** Definitions whose name is written in [file], for finding a definition from its own declaration. */
    fun definedIn(file: Path, name: String): List<InkoDefinition> = named(name).filter { it.file == file }
}
