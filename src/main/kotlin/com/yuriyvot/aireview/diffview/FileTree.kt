package com.yuriyvot.aireview.diffview

data class FileItem(
    val path: String,
    val oldPath: String?,
    val status: String,
    val added: Int?,
    val deleted: Int?,
    val binary: Boolean,
    val contentKey: String,
    val projectPath: String?,
    val projectOldPath: String?,
) {
    val name: String get() = path.substringAfterLast('/')
    val isMeta: Boolean get() = path.endsWith(".meta", ignoreCase = true)
}

class FileTreeDir(var name: String, val key: String) {
    val dirs = LinkedHashMap<String, FileTreeDir>()
    val files = ArrayList<FileItem>()
    var count = 0

    fun sortedDirs(): List<FileTreeDir> = dirs.values.sortedBy { it.name.lowercase() }

    fun sortedFiles(): List<FileItem> = files.sortedBy { it.name.lowercase() }

    private fun add(parts: List<String>, item: FileItem, index: Int) {
        count++
        if (index == parts.size - 1) {
            files += item
            return
        }
        val name = parts[index]
        val child = dirs.getOrPut(name) { FileTreeDir(name, if (key.isEmpty()) name else "$key/$name") }
        child.add(parts, item, index + 1)
    }

    private fun compact() {
        val merged = LinkedHashMap<String, FileTreeDir>()
        dirs.values.forEach { child ->
            child.compact()
            var c = child
            while (c.files.isEmpty() && c.dirs.size == 1) {
                val only = c.dirs.values.first()
                only.name = c.name + "/" + only.name
                c = only
            }
            merged[c.name] = c
        }
        dirs.clear()
        dirs.putAll(merged)
    }

    private fun collect(out: MutableList<FileItem>) {
        sortedDirs().forEach { it.collect(out) }
        out += sortedFiles()
    }

    companion object {
        fun build(items: List<FileItem>): FileTreeDir {
            val root = FileTreeDir("", "")
            items.forEach { root.add(it.path.split('/'), it, 0) }
            root.compact()
            return root
        }

        fun visible(items: List<FileItem>, hideMeta: Boolean): List<FileItem> =
            if (hideMeta) items.filterNot { it.isMeta } else items

        fun order(items: List<FileItem>, flat: Boolean): List<FileItem> =
            if (flat) items.sortedBy { it.path.lowercase() }
            else mutableListOf<FileItem>().also { build(items).collect(it) }
    }
}
