package com.ywwynm.everythingdone.helpers

import android.content.Context
import com.ywwynm.everythingdone.database.ThingFolderDAO
import com.ywwynm.everythingdone.model.Thing

/**
 * 统一的"展示用有效私密"解析入口（见 docs/adr/0011）。
 *
 * 由于文件夹私密采用容器模型——后代记事不加前缀、只靠"有效私密"在展示层保护——
 * 每个展示/通知界面都必须自行结合祖先文件夹判断是否该保护。过去这判断散落各处，
 * 谁忘了谁就会把"处于私密文件夹内、自身无前缀"的记事明文显示出来（单一小部件、
 * 系统通知曾确认泄露）。所有展示/通知界面拿到 Thing 后都应先经此入口，杜绝再漏。
 */
object ThingPrivacyResolver {

    /** 该记事是否"有效私密"：自身私密，或其某个祖先文件夹私密。 */
    @JvmStatic
    fun isEffectivelyPrivate(thing: Thing, folderDAO: ThingFolderDAO): Boolean {
        return thing.isPrivate() || folderDAO.isEffectivelyPrivate(thing.folderId)
    }

    @JvmStatic
    fun isEffectivelyPrivate(context: Context, thing: Thing): Boolean {
        return isEffectivelyPrivate(thing, ThingFolderDAO.getInstance(context)!!)
    }

    /**
     * 返回"展示安全"的记事：若有效私密但自身无前缀，则补上私密前缀，使下游渲染/通知
     * 按私密处理。不改动原对象——需要保护时返回带前缀的副本，否则原样返回。
     */
    @JvmStatic
    fun resolveForPresentation(thing: Thing, folderDAO: ThingFolderDAO): Thing {
        if (thing.isPrivate() || !folderDAO.isEffectivelyPrivate(thing.folderId)) {
            return thing
        }
        val copy = Thing(thing)
        copy.title = Thing.PRIVATE_THING_PREFIX + (copy.title ?: "")
        return copy
    }

    @JvmStatic
    fun resolveForPresentation(context: Context, thing: Thing): Thing {
        return resolveForPresentation(thing, ThingFolderDAO.getInstance(context)!!)
    }
}
