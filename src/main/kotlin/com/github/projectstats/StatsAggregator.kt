package com.github.projectstats

object StatsAggregator {

    fun aggregate(
        result: ScanResult,
        groupBy: GroupBy,
        includeTests: Boolean,
        includeGenerated: Boolean,
        includeResources: Boolean,
        includeOther: Boolean,
    ): List<StatGroup> {
        val filtered = result.files.filter { keep(it, includeTests, includeGenerated, includeResources, includeOther) }
        return when (groupBy) {
            GroupBy.LANGUAGE -> flatGroup(filtered) { it.language }
            GroupBy.MODULE -> flatGroup(filtered) { it.module }
            GroupBy.CATEGORY -> flatGroup(filtered) { it.category.display }
            GroupBy.DIRECTORY -> directoryTree(filtered)
        }
    }

    private fun keep(
        f: FileStat,
        includeTests: Boolean,
        includeGenerated: Boolean,
        includeResources: Boolean,
        includeOther: Boolean,
    ): Boolean = when (f.category) {
        SourceCategory.TEST, SourceCategory.TEST_RESOURCES -> includeTests
        SourceCategory.GENERATED -> includeGenerated
        SourceCategory.RESOURCES -> includeResources
        SourceCategory.OTHER -> includeOther
        SourceCategory.SOURCE -> true
    }

    private class Accum(var total: Long = 0, var nonBlank: Long = 0, var size: Long = 0, var count: Long = 0)

    private inline fun flatGroup(files: List<FileStat>, keyOf: (FileStat) -> String): List<StatGroup> {
        val byKey = HashMap<String, Accum>()
        for (f in files) {
            val acc = byKey.getOrPut(keyOf(f)) { Accum() }
            acc.total += f.totalLines
            acc.nonBlank += f.nonBlankLines
            acc.size += f.sizeBytes
            acc.count += 1
        }
        return byKey.entries.map { (k, v) ->
            StatGroup(k, v.total, v.nonBlank, v.size, v.count)
        }.sortedByDescending { it.totalLines }
    }

    /**
     * Build a directory tree rooted at "/" where each node aggregates its descendants.
     */
    private fun directoryTree(files: List<FileStat>): List<StatGroup> {
        // Mutable tree node
        data class Node(
            val name: String,
            val children: HashMap<String, Node> = HashMap(),
            var total: Long = 0,
            var nonBlank: Long = 0,
            var size: Long = 0,
            var count: Long = 0,
            var isFile: Boolean = false,
        )

        val root = Node("<project>")

        for (f in files) {
            val parts = f.relativePath.split('/').filter { it.isNotEmpty() }
            var node = root
            root.total += f.totalLines; root.nonBlank += f.nonBlankLines
            root.size += f.sizeBytes; root.count += 1
            for ((idx, part) in parts.withIndex()) {
                node = node.children.getOrPut(part) { Node(part) }
                node.total += f.totalLines
                node.nonBlank += f.nonBlankLines
                node.size += f.sizeBytes
                node.count += 1
                if (idx == parts.lastIndex) node.isFile = true
            }
        }

        fun convert(n: Node): StatGroup {
            val kids = n.children.values.map { convert(it) }.sortedByDescending { it.totalLines }
            return StatGroup(n.name, n.total, n.nonBlank, n.size, n.count, kids)
        }
        // Return the top-level children (under <project>); treemap presents them as roots.
        return convert(root).children
    }
}
