package com.setdef.parakeet

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Path

enum class InkoDefinitionKind { MODULE, TYPE, TRAIT, CONSTANT, MODULE_METHOD, STATIC_METHOD, INSTANCE_METHOD, CONSTRUCTOR, FIELD }

/**
 * A definition reported by `inko doc`. [owner] is the type or trait it belongs
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

/** Reads one module's JSON from `inko doc`; [toReal] maps paths out of the mirror it ran in. */
object InkoDocParser {
    fun parse(json: String, toReal: (Path) -> Path = { it }): List<InkoDefinition> {
        val root = JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: return emptyList()
        val module = root.string("name") ?: return emptyList()
        val moduleFile = toReal(Path.of(root.string("file") ?: return emptyList()))
        val result = mutableListOf(
            InkoDefinition(InkoDefinitionKind.MODULE, module, module, null, moduleFile, 1, 1, "import $module", root.string("documentation").orEmpty()),
        )

        fun add(kind: InkoDefinitionKind, obj: JsonObject, owner: String?, related: List<InkoTypeRef> = emptyList()) {
            val name = obj.string("name") ?: return
            val location = obj.getAsJsonObject("location") ?: return
            // Methods carry their own file: default trait methods live in the trait's module.
            val file = obj.string("file")?.let { toReal(Path.of(it)) } ?: moduleFile
            val line = location.getAsJsonObject("lines")?.get("start")?.asInt ?: 1
            val column = location.getAsJsonObject("columns")?.get("start")?.asInt ?: 1
            result += InkoDefinition(kind, name, module, owner, file, line, column, obj.string("type"), obj.string("documentation").orEmpty(), related)
        }

        root.objects("constants").forEach { add(InkoDefinitionKind.CONSTANT, it, null) }
        root.objects("methods").forEach { add(InkoDefinitionKind.MODULE_METHOD, it, null) }
        for (type in root.objects("types")) {
            add(InkoDefinitionKind.TYPE, type, null, type.typeRefs("implemented_traits"))
            val owner = type.string("name")
            type.objects("constructors").forEach { add(InkoDefinitionKind.CONSTRUCTOR, it, owner) }
            type.objects("fields").forEach { add(InkoDefinitionKind.FIELD, it, owner) }
            type.objects("static_methods").forEach { add(InkoDefinitionKind.STATIC_METHOD, it, owner) }
            type.objects("instance_methods").forEach { add(InkoDefinitionKind.INSTANCE_METHOD, it, owner) }
        }
        for (trait in root.objects("traits")) {
            add(InkoDefinitionKind.TRAIT, trait, null, trait.typeRefs("implementations"))
            val owner = trait.string("name")
            trait.objects("required_methods").forEach { add(InkoDefinitionKind.INSTANCE_METHOD, it, owner) }
            trait.objects("default_methods").forEach { add(InkoDefinitionKind.INSTANCE_METHOD, it, owner) }
        }
        return result
    }

    private fun JsonObject.string(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.typeRefs(key: String): List<InkoTypeRef> = objects(key).mapNotNull { ref ->
        InkoTypeRef(ref.string("module") ?: return@mapNotNull null, ref.string("name") ?: return@mapNotNull null)
    }

    private fun JsonObject.objects(key: String): List<JsonObject> =
        (get(key) as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }.orEmpty()
}
