package com.ywwynm.everythingdone.interaction

import org.junit.Assert.*
import org.junit.Test

class UiTransitionStateTest {
    @Test fun dialogAndPageCompeteBeforeFragmentTransactionRuns() {
        val state = UiTransitionState()
        assertNotNull(state.open("digit"))
        assertNull(state.open("digit"))
        assertNull(state.open("color"))
        assertNull(state.leave())
    }

    @Test fun pageWinsAgainstDialogAndAnotherPage() {
        val state = UiTransitionState()
        assertNotNull(state.leave())
        assertNull(state.open("digit"))
        assertNull(state.leave())
    }

    @Test fun transparentDialogNeverRestoresInputToUnderlyingPage() {
        val state = UiTransitionState()
        val dialog = state.open("attachment")!!
        assertFalse(state.canInteract(state.root))
        assertFalse(state.canInteract(dialog))
        state.ready(dialog)
        assertTrue(state.canInteract(dialog))
        assertNull(state.open("color", state.root))
        assertNull(state.leave(state.root))
        state.closing(dialog)
        assertFalse(state.canInteract(state.root))
        assertFalse(state.canInteract(dialog))
        state.release(dialog)
        assertTrue(state.canInteract(state.root))
    }

    @Test fun retainedParentCanOpenChildAndReceiveInputAgain() {
        val state = UiTransitionState()
        val parent = state.open("color")!!
        state.ready(parent)
        val child = state.open("camera", parent)!!
        state.ready(child)
        assertFalse(state.canInteract(parent))
        assertTrue(state.canInteract(child))
        state.release(child)
        assertTrue(state.canInteract(parent))
        assertNull(state.open("stale", child))
    }

    @Test fun replacementDoesNotUnlockRootWhileOldAnimationFinishes() {
        val state = UiTransitionState()
        val old = state.open("attachment")!!
        state.ready(old)
        state.closing(old)
        val next = state.open("record", old)!!
        state.release(old)
        state.ready(next)
        state.release(old)
        assertSame(next, state.current)
        assertFalse(state.canInteract(state.root))
        assertTrue(state.canInteract(next))
    }

    @Test fun lateAppearanceCallbackCannotReopenClosingWindow() {
        val state = UiTransitionState()
        val dialog = state.open("loading")!!
        state.closing(dialog)
        state.ready(dialog)
        assertFalse(state.canInteract(dialog))
        assertNotNull(state.open("result", dialog))
    }

    @Test fun failedLaunchAndRealReturnReleaseOnlyTheirOwnDeparture() {
        val state = UiTransitionState()
        val failed = state.leave()!!
        state.cancelDeparture(failed)
        val next = state.leave()!!
        state.cancelDeparture(failed)
        state.resumed()
        assertFalse(state.canInteract(state.root))
        state.paused()
        state.resumed()
        assertTrue(state.canInteract(state.root))
        state.cancelDeparture(next)
    }

    @Test fun restoredDialogsKeepTheirStackAndBlockRoot() {
        val state = UiTransitionState()
        val parent = state.restore("parent")
        val child = state.restore("child")
        state.ready(parent)
        state.ready(child)
        assertFalse(state.canInteract(state.root))
        assertFalse(state.canInteract(parent))
        state.release(child)
        assertTrue(state.canInteract(parent))
    }
}
