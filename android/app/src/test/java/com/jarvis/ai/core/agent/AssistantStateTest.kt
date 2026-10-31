package com.jarvis.ai.core.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantStateTest {

    @Test
    fun `idle is always reachable so cancel always works`() {
        // A running action the user cannot abort is a bug, not a state.
        for (state in AssistantState.entries) {
            assertTrue("cannot cancel from $state", state.canGoTo(AssistantState.IDLE))
        }
    }

    @Test
    fun `error and disconnected are reachable from anywhere`() {
        for (state in AssistantState.entries) {
            assertTrue("$state -> ERROR", state.canGoTo(AssistantState.ERROR))
            assertTrue("$state -> DISCONNECTED", state.canGoTo(AssistantState.DISCONNECTED))
        }
    }

    @Test
    fun `local router may skip understanding`() {
        assertTrue(AssistantState.LISTENING.canGoTo(AssistantState.EXECUTING))
    }

    @Test
    fun `idle cannot jump straight to success`() {
        assertFalse(AssistantState.IDLE.canGoTo(AssistantState.SUCCESS))
    }

    @Test
    fun `normal voice cycle is legal end to end`() {
        val cycle = listOf(
            AssistantState.IDLE,
            AssistantState.LISTENING,
            AssistantState.UNDERSTANDING,
            AssistantState.THINKING,
            AssistantState.SPEAKING,
            AssistantState.IDLE,
        )
        for (i in 0 until cycle.size - 1) {
            assertTrue("${cycle[i]} -> ${cycle[i + 1]}", cycle[i].canGoTo(cycle[i + 1]))
        }
    }

    @Test
    fun `busy flag covers exactly the in-flight states`() {
        val busy = AssistantState.entries.filter { it.isBusy }.toSet()
        assertTrue(
            busy == setOf(
                AssistantState.LISTENING,
                AssistantState.UNDERSTANDING,
                AssistantState.THINKING,
                AssistantState.SPEAKING,
                AssistantState.EXECUTING,
            ),
        )
    }

    @Test
    fun `every state has a transition entry`() {
        for (state in AssistantState.entries) {
            assertTrue("missing transitions for $state", TRANSITIONS.containsKey(state))
        }
    }
}
