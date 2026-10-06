package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.CategoryService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.server.config.Warn
import xyz.felismp.shoparchive.server.config.YamlConfigFile
import xyz.felismp.shoparchive.server.users.quoted
import xyz.felismp.shoparchive.shared.AppliesTo
import xyz.felismp.shoparchive.shared.CategoryDto
import xyz.felismp.shoparchive.shared.CreateCategoryRequest
import xyz.felismp.shoparchive.shared.LocalizedName
import xyz.felismp.shoparchive.shared.UpdateCategoryRequest
import xyz.felismp.shoparchive.shared.isSlug
import xyz.felismp.shoparchive.shared.wire

internal data class Category(val key: String, val name: LocalizedName, val appliesTo: AppliesTo, val archived: Boolean) {
    fun toDto() = CategoryDto(key, name, appliesTo, archived)
}

/** `data/categories.yml`. The key is what entries store, so it never changes; the names can. */
internal object CategoriesFile : YamlConfigFile<List<Category>>("file-version", 1, emptyList()) {
    override val path = "data/categories.yml"
    override val title = "ShopArchive categories. Change them with the console (category add, category name), or edit by hand and run 'reload data'."

    private val KNOWN = setOf("key", "name", "applies-to", "archived")

    override fun resolve(content: Map<String, Any?>, warn: Warn): List<Category> {
        for (name in content.keys) if (name != "categories") warn("unknown key '$name' (ignored, and not kept when the file is rewritten)")
        val entries = content["categories"] ?: return emptyList()
        if (entries !is List<*>) {
            warn("categories: expected a list of categories; ignored")
            return emptyList()
        }
        val result = LinkedHashMap<String, Category>()
        entries.forEachIndexed { index, entry ->
            val where = "categories[$index]"
            @Suppress("UNCHECKED_CAST")
            val fields = entry as? Map<String, Any?>
            val key = (fields?.get("key") as? String)?.trim()
            if (fields == null || key == null || !isSlug(key)) {
                warn("$where: needs a key of 1 to 32 characters from a-z, 0-9 and -; entry ignored")
                return@forEachIndexed
            }
            if (key in result) {
                warn("$where: key '$key' is listed twice; the later entry is ignored")
                return@forEachIndexed
            }
            for (name in fields.keys) if (name !in KNOWN) warn("$where: unknown key '$name' (ignored, and not kept when the file is rewritten)")
            val appliesTo = AppliesTo.entries.firstOrNull { it.wire() == (fields["applies-to"] as? String)?.trim()?.lowercase() }
            if (appliesTo == null) {
                warn("$where: applies-to '${fields["applies-to"] ?: "(empty)"}' is not income, expense or both; entry ignored")
                return@forEachIndexed
            }
            val archived = when ((fields["archived"] as? String)?.trim()?.lowercase()) {
                null, "false" -> false
                "true" -> true
                else -> false.also { warn("$where: archived '${fields["archived"]}' is not true or false; using false") }
            }
            @Suppress("UNCHECKED_CAST")
            val names = fields["name"] as? Map<String, Any?> ?: emptyMap()
            fun nameIn(language: String, fallback: String) = (names[language] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: fallback
            val en = nameIn("en", key)
            result[key] = Category(key, LocalizedName(lo = nameIn("lo", en), th = nameIn("th", en), en = en), appliesTo, archived)
        }
        return result.values.toList()
    }

    override fun renderBody(value: List<Category>): String {
        if (value.isEmpty()) return "categories: []\n"
        return value.joinToString("", "categories:\n") { c ->
            "  - key: ${c.key}\n" +
                "    name: { lo: ${quoted(c.name.lo)}, th: ${quoted(c.name.th)}, en: ${quoted(c.name.en)} }\n" +
                "    applies-to: ${c.appliesTo.wire()}\n" +
                "    archived: ${c.archived}\n"
        }
    }
}

internal class CategoryStore(private val files: DataFileStore<List<Category>>) {
    /** Reads the file again. @throws xyz.felismp.shoparchive.server.config.ConfigFileException it cannot be understood */
    fun load() = files.load()

    fun all(): List<Category> = files.value

    fun find(key: String): Category? = all().firstOrNull { it.key == key }

    fun add(key: String, name: LocalizedName, appliesTo: AppliesTo): Category {
        if (!isSlug(key)) throw badRequest("A category key is 1 to 32 characters from a-z, 0-9 and -.")
        val category = Category(key, cleanName(name), appliesTo, archived = false)
        files.update { list ->
            if (list.any { it.key == key }) throw conflict("A category '$key' exists already.")
            list + category
        }
        return category
    }

    fun update(key: String, name: LocalizedName?, appliesTo: AppliesTo?, archived: Boolean?): Category {
        val clean = name?.let(::cleanName)
        lateinit var changed: Category
        files.update { list ->
            val old = list.firstOrNull { it.key == key } ?: throw notFound("No category '$key'.")
            changed = old.copy(name = clean ?: old.name, appliesTo = appliesTo ?: old.appliesTo, archived = archived ?: old.archived)
            list.map { if (it.key == key) changed else it }
        }
        return changed
    }

    private fun cleanName(name: LocalizedName): LocalizedName {
        val clean = LocalizedName(name.lo.trim(), name.th.trim(), name.en.trim())
        if (clean.lo.isEmpty() || clean.th.isEmpty() || clean.en.isEmpty()) throw badRequest("A category needs a name in Lao, Thai and English.")
        return clean
    }
}

internal class DefaultCategoryService(private val categories: CategoryStore, private val access: Access) : CategoryService {
    override fun list(principal: Principal): List<CategoryDto> = categories.all().map { it.toDto() }

    override fun create(principal: Principal, request: CreateCategoryRequest): CategoryDto {
        access.require(principal, CATEGORIES_MANAGE_NODE)
        return categories.add(request.key, request.name, request.appliesTo).toDto()
    }

    override fun update(principal: Principal, key: String, request: UpdateCategoryRequest): CategoryDto {
        access.require(principal, CATEGORIES_MANAGE_NODE)
        return categories.update(key, request.name, request.appliesTo, request.archived).toDto()
    }
}
