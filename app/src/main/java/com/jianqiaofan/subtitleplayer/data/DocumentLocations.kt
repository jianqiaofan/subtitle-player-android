package com.jianqiaofan.subtitleplayer.data

object DocumentLocations {
    fun parentId(documentId: String): String? {
        val slash = documentId.lastIndexOf('/')
        if (slash <= 0) return null
        return documentId.substring(0, slash)
    }

    fun isWithinTree(treeId: String, directoryId: String): Boolean {
        if (treeId.isEmpty() || directoryId.isEmpty()) return false
        return directoryId == treeId || directoryId.startsWith("$treeId/")
    }

    fun directoryLabel(directoryId: String): String {
        val decoded = try {
            java.net.URLDecoder.decode(directoryId, Charsets.UTF_8.name())
        } catch (_: Exception) {
            directoryId
        }
        val path = decoded.substringAfterLast(':')
        return path.substringAfterLast('/').ifBlank { "文件夹" }
    }
}
