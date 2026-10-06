package xyz.felismp.shoparchive.server.records

import xyz.felismp.shoparchive.api.BranchService
import xyz.felismp.shoparchive.api.Principal
import xyz.felismp.shoparchive.server.config.Warn
import xyz.felismp.shoparchive.server.config.YamlConfigFile
import xyz.felismp.shoparchive.server.users.quoted
import xyz.felismp.shoparchive.shared.BranchDto
import xyz.felismp.shoparchive.shared.CreateBranchRequest
import xyz.felismp.shoparchive.shared.UpdateBranchRequest
import xyz.felismp.shoparchive.shared.isSlug

internal data class Branch(val key: String, val displayName: String, val archived: Boolean) {
    fun toDto() = BranchDto(key, displayName, archived)
}

/** `data/branches.yml`. A branch is never deleted: archived, it takes no new entries or days but its records stay. */
internal object BranchesFile : YamlConfigFile<List<Branch>>("file-version", 1, emptyList()) {
    override val path = "data/branches.yml"
    override val title = "ShopArchive branches. Change them with the console (branch add, branch archive), or edit by hand and run 'reload data'."

    private val KNOWN = setOf("key", "display-name", "archived")

    override fun resolve(content: Map<String, Any?>, warn: Warn): List<Branch> {
        for (name in content.keys) if (name != "branches") warn("unknown key '$name' (ignored, and not kept when the file is rewritten)")
        val entries = content["branches"] ?: return emptyList()
        if (entries !is List<*>) {
            warn("branches: expected a list of branches; ignored")
            return emptyList()
        }
        val result = LinkedHashMap<String, Branch>()
        entries.forEachIndexed { index, entry ->
            val where = "branches[$index]"
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
            val archived = when ((fields["archived"] as? String)?.trim()?.lowercase()) {
                null, "false" -> false
                "true" -> true
                else -> false.also { warn("$where: archived '${fields["archived"]}' is not true or false; using false") }
            }
            result[key] = Branch(key, (fields["display-name"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: key, archived)
        }
        return result.values.toList()
    }

    override fun renderBody(value: List<Branch>): String {
        if (value.isEmpty()) return "branches: []\n"
        return value.joinToString("", "branches:\n") { branch ->
            "  - key: ${branch.key}\n    display-name: ${quoted(branch.displayName)}\n    archived: ${branch.archived}\n"
        }
    }
}

/** The branches, from `data/branches.yml`. Errors are [xyz.felismp.shoparchive.api.ApiError]s, which the console prints as they are. */
internal class BranchStore(private val files: DataFileStore<List<Branch>>) {
    /** Reads the file again. @throws xyz.felismp.shoparchive.server.config.ConfigFileException it cannot be understood */
    fun load() = files.load()

    fun all(): List<Branch> = files.value

    fun find(key: String): Branch? = all().firstOrNull { it.key == key }

    fun add(key: String, displayName: String): Branch {
        if (!isSlug(key)) throw badRequest("A branch key is 1 to 32 characters from a-z, 0-9 and -.")
        val name = displayName.trim()
        if (name.isEmpty()) throw badRequest("A branch needs a display name.")
        val branch = Branch(key, name, archived = false)
        files.update { list ->
            if (list.any { it.key == key }) throw conflict("A branch '$key' exists already.")
            list + branch
        }
        return branch
    }

    fun update(key: String, displayName: String?, archived: Boolean?): Branch {
        val name = displayName?.trim()
        if (name != null && name.isEmpty()) throw badRequest("A branch needs a display name.")
        lateinit var changed: Branch
        files.update { list ->
            val old = list.firstOrNull { it.key == key } ?: throw notFound("No branch '$key'.")
            changed = old.copy(displayName = name ?: old.displayName, archived = archived ?: old.archived)
            list.map { if (it.key == key) changed else it }
        }
        return changed
    }
}

internal class DefaultBranchService(private val branches: BranchStore, private val access: Access) : BranchService {
    override fun list(principal: Principal): List<BranchDto> =
        branches.all().filter { access.canBranch(principal, it.key) }.map { it.toDto() }

    override fun create(principal: Principal, request: CreateBranchRequest): BranchDto {
        access.require(principal, BRANCHES_MANAGE_NODE)
        return branches.add(request.key, request.displayName).toDto()
    }

    override fun update(principal: Principal, key: String, request: UpdateBranchRequest): BranchDto {
        access.require(principal, BRANCHES_MANAGE_NODE)
        return branches.update(key, request.displayName, request.archived).toDto()
    }
}
