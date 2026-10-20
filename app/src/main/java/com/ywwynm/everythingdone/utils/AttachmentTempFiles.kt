package com.ywwynm.everythingdone.utils

import java.io.File

/** 原名保存在清单中；临时文件只保留常见扩展名，避免添加前缀后超过文件系统名称长度。 */
internal object AttachmentTempFiles {
    private val extensionPattern = Regex("[a-zA-Z0-9]{1,16}")

    fun create(directory: File, entryName: String): File {
        val extension = entryName.substringAfterLast('/').substringAfterLast('.', "")
        val suffix = if (extensionPattern.matches(extension)) ".$extension" else ""
        // 名称由本机原子分配，不把外部条目名拼入真实路径；同内容的多个附件也拥有独立文件。
        return File.createTempFile("attachment-", suffix, directory)
    }
}
