package com.ywwynm.everythingdone.helpers

import java.io.File

/** 本地 GIF 使用文件映射，避免 String → InputStream 解码时整文件扩容、复制到堆。 */
internal fun localAnimatedImageModel(pathName: String): Any {
    if (!AttachmentHelper.isAnimatedImageCandidate(pathName)) return pathName
    val file = File(pathName)
    // URI、远程或不存在的来源继续交给原有 String loader；视频和普通照片也保持原路径。
    return if (file.isFile) file else pathName
}
