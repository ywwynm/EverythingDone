package com.ywwynm.everythingdone.helpers

import android.content.Context
import com.ywwynm.everythingdone.Def
import com.ywwynm.everythingdone.R
import com.ywwynm.everythingdone.model.Thing
import com.ywwynm.everythingdone.model.ThingWidgetInfo

object HomeActionWordingHelper {

    enum class StateTarget {
        ROOT,
        CURRENT_FOLDER,
        SELECTED_THINGS,
        SELECTED_FOLDERS,
        SELECTED_ITEMS
    }

    enum class StructuralTarget {
        CURRENT_FOLDER,
        SELECTED_FOLDER,
        SELECTED_FOLDERS,
        SELECTED_ITEMS
    }

    enum class StructuralAction {
        DISSOLVE_FOLDER,
        DELETE_FOLDER_FOREVER
    }

    data class ActionWording(
        val actionTitle: String,
        val dialogTitle: String = actionTitle,
        val dialogBody: String = "",
        val confirmText: String
    )

    fun stateActionTitle(
        context: Context,
        status: Int,
        stateAfter: Int,
        target: StateTarget
    ): String {
        val res = stateActionTitleRes(status, stateAfter, target)
        return if (target == StateTarget.CURRENT_FOLDER ||
            target == StateTarget.SELECTED_FOLDERS ||
            target == StateTarget.SELECTED_ITEMS
        ) {
            context.getString(res, stateTargetName(context, target))
        } else {
            context.getString(res)
        }
    }

    fun stateActionWording(
        context: Context,
        status: Int,
        stateAfter: Int,
        target: StateTarget,
        count: Int,
        includesSubfolders: Boolean,
        typeFilterMask: Int? = null,
        excludesDoing: Boolean = false,
        searchScoped: Boolean = false
    ): ActionWording {
        val title = stateActionTitle(context, status, stateAfter, target)
        // 合成数量后的括号子句：子文件夹提示与"不含正在做的记事"都在时，合进同一对括号，
        // 避免出现连续两个括号。
        val clause = when {
            includesSubfolders && excludesDoing ->
                context.getString(R.string.scope_includes_subfolders_and_excludes_doing)
            includesSubfolders -> context.getString(R.string.scope_includes_subfolders)
            excludesDoing -> context.getString(R.string.scope_excludes_doing)
            else -> ""
        }
        val bodyRes = when (stateAfter) {
            Thing.UNDERWAY ->
                if (status == Def.ThingStatus.DELETED) {
                    R.string.home_action_confirm_restore_deleted_to_previous
                } else {
                    R.string.home_action_confirm_plain
                }
            Thing.DELETED -> R.string.home_action_confirm_recoverable
            Thing.DELETED_FOREVER -> R.string.home_action_confirm_irreversible
            else -> R.string.home_action_confirm_plain
        }
        var body = context.getString(bodyRes, title, count, clause)
        val typeTitle = typeFilterMask?.let {
            ThingWidgetInfo.getTypeFilterTitle(context, it)
        }
        val scopeReminder = stateScopeReminder(context, typeTitle, target, searchScoped)
        if (scopeReminder != null) {
            body += "\n" + scopeReminder
        }
        return ActionWording(
            actionTitle = title,
            dialogBody = body,
            confirmText = context.getString(R.string.confirm)
        )
    }

    private fun stateScopeReminder(
        context: Context,
        typeTitle: String?,
        target: StateTarget,
        searchScoped: Boolean
    ): String? {
        val typeScoped = typeTitle != null && target != StateTarget.SELECTED_THINGS
        return when {
            typeScoped && searchScoped ->
                context.getString(R.string.folder_op_scope_type_and_search, typeTitle)
            typeScoped -> context.getString(R.string.folder_op_scope_only_type, typeTitle)
            searchScoped -> context.getString(R.string.folder_op_scope_only_search)
            else -> null
        }
    }

    fun stickyTitle(context: Context, allSticky: Boolean): String {
        return context.getString(
            if (allSticky) R.string.home_action_unpin else R.string.home_action_pin
        )
    }

    fun privateTitle(context: Context, allPrivate: Boolean): String {
        return context.getString(
            if (allPrivate) R.string.home_action_cancel_private else R.string.home_action_set_private
        )
    }

    fun cannotSetPrivateTitle(context: Context): String {
        return context.getString(R.string.home_action_cannot_set_private)
    }

    fun structuralActionTitle(
        context: Context,
        action: StructuralAction,
        target: StructuralTarget
    ): String {
        val res = when (action) {
            StructuralAction.DISSOLVE_FOLDER -> when (target) {
                StructuralTarget.CURRENT_FOLDER -> R.string.home_structure_title_dissolve_current_folder
                else -> R.string.home_structure_title_dissolve_selected_folder
            }
            StructuralAction.DELETE_FOLDER_FOREVER -> when (target) {
                StructuralTarget.CURRENT_FOLDER -> R.string.home_structure_title_delete_current_folder_forever
                StructuralTarget.SELECTED_FOLDER -> R.string.home_structure_title_delete_selected_folder_forever
                StructuralTarget.SELECTED_FOLDERS -> R.string.home_structure_title_delete_selected_folders_forever
                StructuralTarget.SELECTED_ITEMS -> R.string.home_structure_title_delete_selected_items_forever
            }
        }
        return context.getString(res)
    }

    fun structuralActionWording(
        context: Context,
        action: StructuralAction,
        target: StructuralTarget,
        impact: String,
        hiddenScopeClause: String? = null,
        impactIsEmpty: Boolean = false
    ): ActionWording {
        val title = structuralActionTitle(context, action, target)
        val bodyRes = when (action) {
            StructuralAction.DISSOLVE_FOLDER -> {
                if (impactIsEmpty) {
                    R.string.home_structure_dissolve_empty_body
                } else {
                    R.string.home_structure_dissolve_body
                }
            }
            StructuralAction.DELETE_FOLDER_FOREVER -> {
                when {
                    target == StructuralTarget.SELECTED_ITEMS ->
                        R.string.home_structure_delete_items_forever_body
                    impactIsEmpty ->
                        R.string.home_structure_delete_folder_forever_empty_body
                    else ->
                        R.string.home_structure_delete_folder_forever_body
                }
            }
        }
        var body = if (action == StructuralAction.DELETE_FOLDER_FOREVER &&
            target == StructuralTarget.SELECTED_ITEMS
        ) {
            context.getString(bodyRes, impact)
        } else {
            context.getString(bodyRes, structuralTargetName(context, target), impact)
        }
        if (hiddenScopeClause != null) {
            body += "\n" + context.getString(R.string.folder_op_reminder_subtree, hiddenScopeClause)
        }
        return ActionWording(
            actionTitle = title,
            dialogBody = body,
            confirmText = context.getString(R.string.confirm)
        )
    }

    private fun stateTargetName(context: Context, target: StateTarget): String {
        val res = when (target) {
            StateTarget.CURRENT_FOLDER -> R.string.home_state_target_current_folder
            StateTarget.SELECTED_FOLDERS -> R.string.home_state_target_selected_folders
            StateTarget.SELECTED_ITEMS -> R.string.home_state_target_selected_items
            else -> throw IllegalArgumentException("Target $target has no container name")
        }
        return context.getString(res)
    }

    private fun structuralTargetName(context: Context, target: StructuralTarget): String {
        val res = when (target) {
            StructuralTarget.CURRENT_FOLDER -> R.string.home_structure_target_current_folder
            StructuralTarget.SELECTED_FOLDER -> R.string.home_structure_target_selected_folder
            StructuralTarget.SELECTED_FOLDERS -> R.string.home_structure_target_selected_folders
            StructuralTarget.SELECTED_ITEMS -> R.string.home_structure_target_selected_items
        }
        return context.getString(res)
    }

    private fun stateActionTitleRes(
        status: Int,
        stateAfter: Int,
        target: StateTarget
    ): Int {
        if (target == StateTarget.SELECTED_THINGS) {
            return when (stateAfter) {
                Thing.FINISHED -> R.string.home_state_title_finish_selected_things
                Thing.DELETED -> R.string.home_state_title_delete_selected_things
                Thing.UNDERWAY -> R.string.home_state_title_restore_selected_things
                Thing.DELETED_FOREVER -> R.string.home_state_title_delete_forever_selected_things
                else -> throw IllegalArgumentException("Unsupported selected Thing stateAfter=$stateAfter")
            }
        }

        val root = target == StateTarget.ROOT
        return when {
            stateAfter == Thing.FINISHED ->
                if (root) R.string.home_state_title_finish_root
                else R.string.home_state_title_finish_container

            status == Def.ThingStatus.UNDERWAY && stateAfter == Thing.DELETED ->
                if (root) R.string.home_state_title_delete_underway_root
                else R.string.home_state_title_delete_underway_container

            status == Def.ThingStatus.FINISHED && stateAfter == Thing.DELETED ->
                if (root) R.string.home_state_title_delete_finished_root
                else R.string.home_state_title_delete_finished_container

            status == Def.ThingStatus.FINISHED && stateAfter == Thing.UNDERWAY ->
                if (root) R.string.home_state_title_restore_finished_root
                else R.string.home_state_title_restore_finished_container

            status == Def.ThingStatus.DELETED && stateAfter == Thing.UNDERWAY ->
                if (root) R.string.home_state_title_restore_deleted_root
                else R.string.home_state_title_restore_deleted_container

            stateAfter == Thing.DELETED_FOREVER ->
                if (root) R.string.home_state_title_delete_forever_deleted_root
                else R.string.home_state_title_delete_forever_deleted_container

            else -> throw IllegalArgumentException(
                "Unsupported state action status=$status stateAfter=$stateAfter target=$target"
            )
        }
    }
}
