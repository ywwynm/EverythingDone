package com.ywwynm.everythingdone.thingfile

import android.content.Context
import android.util.Log

import com.ywwynm.everythingdone.database.ThingDAO
import com.ywwynm.everythingdone.helpers.AttachmentHelper
import com.ywwynm.everythingdone.model.Thing

import java.io.File
import java.security.MessageDigest

/**
 * 内容指纹，用来在导入预览里标出「这条你已经有了」。
 *
 * 思路沿用 cloud-sync 首绑定归并的内容指纹去重。指纹只覆盖**标题、正文与附件的体积清单**：
 * 附件不算内容哈希——一条带 100 MB 视频的记事，为了判重去读一遍整个文件不值得，而
 * 「同一份记事文件被导入两次」这个真实场景靠体积清单已经足够分辨。
 */
object ThingFingerprint {

    private const val TAG: String = "ThingFingerprint"

    private const val SEPARATOR: String = "\u0000"

    /** 本机已有记事的指纹集合。导入不是高频操作，每次现算即可，不做缓存。 */
    @JvmStatic
    fun existingFingerprints(context: Context): Set<String> {
        val result = HashSet<String>()
        val dao = ThingDAO.getInstance(context) ?: return result
        // 回收站里的不参与判重：用户把它删了又收到同一条，应当算新的一条
        for (state in intArrayOf(Thing.UNDERWAY, Thing.FINISHED)) {
            for (thing in dao.getAllUserThingsByState(state)) {
                result.add(ofExisting(thing))
            }
        }
        return result
    }

    /** 给一组待导入的记事打上重复标记。 */
    @JvmStatic
    fun markDuplicates(context: Context, pending: List<PendingThing>) {
        if (pending.isEmpty()) {
            return
        }
        val existing = existingFingerprints(context)
        for (p in pending) {
            p.duplicate = existing.contains(ofPending(p))
        }
    }

    private fun ofExisting(thing: Thing): String {
        return of(
            thing.getTitleToDisplay() ?: "",
            thing.content ?: "",
            attachmentSizes(thing.attachment)
        )
    }

    private fun ofPending(p: PendingThing): String {
        return of(
            p.thing.getTitleToDisplay() ?: "",
            p.thing.content ?: "",
            p.attachmentFiles.map { it.length() }
        )
    }

    private fun of(title: String, content: String, sizes: List<Long>): String {
        val sb = StringBuilder()
        sb.append(title).append(SEPARATOR).append(content).append(SEPARATOR)
        for (size in sizes) {
            sb.append(size).append(',')
        }
        return md5(sb.toString())
    }

    private fun attachmentSizes(attachment: String?): List<Long> {
        if (attachment.isNullOrEmpty() || !attachment.contains(AttachmentHelper.SIGNAL)) {
            return emptyList()
        }
        val result = ArrayList<Long>()
        val typePathNames = attachment.split(AttachmentHelper.SIGNAL)
        for (i in 1 until typePathNames.size) {
            val typePathName = typePathNames[i]
            if (typePathName.isEmpty()) {
                continue
            }
            val file = File(typePathName.substring(1))
            if (file.exists()) {
                result.add(file.length())
            }
        }
        return result
    }

    private fun md5(text: String): String {
        return try {
            val digest = MessageDigest.getInstance("MD5").digest(text.toByteArray())
            val sb = StringBuilder(digest.size * 2)
            for (b in digest) {
                sb.append(String.format("%02x", b))
            }
            sb.toString()
        } catch (e: Exception) {
            Log.w(TAG, "算指纹失败，退回原文比较", e)
            text
        }
    }
}
