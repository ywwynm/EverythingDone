package com.ywwynm.everythingdone.helpers

import com.ywwynm.everythingdone.model.Thing
import java.io.File

object ThingCardMediaHelper {

    data class MediaSource(
        val typePathName: String,
        val type: Int,
        val pathName: String,
        val fileSize: Long,
        val lastModified: Long
    ) {
        val isVideo: Boolean
            get() = type == AttachmentHelper.VIDEO
    }

    @JvmStatic
    fun resolveEffectiveMediaSource(thing: Thing?): MediaSource? {
        if (thing == null) return null
        return resolveEffectiveMediaSource(
            thing.attachment,
            thing.thingCardAppearance.mediaSourceKey
        )
    }

    @JvmStatic
    fun resolveEffectiveMediaSource(
        attachment: String?,
        mediaSourceKey: String?
    ): MediaSource? {
        val sources = getAvailableMediaSources(attachment)
        if (sources.isEmpty()) return null
        if (!mediaSourceKey.isNullOrEmpty()) {
            for (source in sources) {
                if (source.typePathName == mediaSourceKey) return source
            }
        }
        return sources[0]
    }

    @JvmStatic
    fun getAvailableMediaSources(attachment: String?): List<MediaSource> {
        if (attachment.isNullOrEmpty() || attachment == "to QQ") return emptyList()
        val sources = ArrayList<MediaSource>()
        val typePathNames = attachment.split(AttachmentHelper.SIGNAL.toRegex()).toTypedArray()
        for (i in 1 until typePathNames.size) {
            toMediaSource(typePathNames[i])?.let { sources.add(it) }
        }
        return sources
    }

    @JvmStatic
    fun getMediaSourceKeysFromAttachment(attachment: String?): Set<String> {
        if (attachment.isNullOrEmpty() || attachment == "to QQ") return emptySet()
        val keys = LinkedHashSet<String>()
        val typePathNames = attachment.split(AttachmentHelper.SIGNAL.toRegex()).toTypedArray()
        for (i in 1 until typePathNames.size) {
            if (isImageOrVideoTypePathName(typePathNames[i])) {
                keys.add(typePathNames[i])
            }
        }
        return keys
    }

    @JvmStatic
    fun toMediaSource(typePathName: String?): MediaSource? {
        if (typePathName.isNullOrEmpty() || typePathName.length < 2) return null
        val type = when (typePathName[0].toString().toIntOrNull()) {
            AttachmentHelper.IMAGE -> AttachmentHelper.IMAGE
            AttachmentHelper.VIDEO -> AttachmentHelper.VIDEO
            else -> return null
        }
        val pathName = typePathName.substring(1, typePathName.length)
        val file = File(pathName)
        if (!file.exists()) return null
        return MediaSource(
            typePathName = typePathName,
            type = type,
            pathName = pathName,
            fileSize = file.length(),
            lastModified = file.lastModified()
        )
    }

    private fun isImageOrVideoTypePathName(typePathName: String?): Boolean {
        if (typePathName.isNullOrEmpty()) return false
        return when (typePathName[0].toString().toIntOrNull()) {
            AttachmentHelper.IMAGE,
            AttachmentHelper.VIDEO -> true
            else -> false
        }
    }
}
