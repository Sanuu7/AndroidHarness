package com.androidharness.app.ui.chat.components

import com.androidharness.app.agent.TodoItem
import com.androidharness.app.agent.TodoItem.Status
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskPeekTest {
    private val plan = listOf(TodoItem("Plan", Status.IN_PROGRESS), TodoItem("Build"), TodoItem("Check"))
    private val advanced = listOf(plan[0].copy(status = Status.COMPLETED), plan[1].copy(status = Status.IN_PROGRESS), plan[2])

    @Test fun onlyANewListOpensByItself() {
        assertEquals(TaskPeek.CREATED_MS, TaskPeek.duration(emptyList(), plan))
        assertNull("Updates animate the pill instead of opening the list", TaskPeek.duration(plan, advanced))
        assertNull(TaskPeek.duration(plan, plan))
        assertNull(TaskPeek.duration(plan, emptyList()))
    }

    @Test fun updatesAreChangesToAListAlreadyShowing() {
        assertTrue(TaskPeek.isUpdate(plan, advanced))
        assertFalse(TaskPeek.isUpdate(emptyList(), plan))
        assertFalse(TaskPeek.isUpdate(plan, plan))
        assertFalse(TaskPeek.isUpdate(plan, emptyList()))
    }

    @Test fun theFinishedTaskIsTheLastOneThatJustCompleted() {
        assertEquals("Plan", TaskPeek.finished(plan, advanced))
        val two = advanced.map { it.copy(status = Status.COMPLETED) }
        assertEquals("Build", TaskPeek.finished(advanced.take(2) + plan[2], listOf(two[0], two[1], plan[2])))
        assertNull("Starting a task is not finishing one", TaskPeek.finished(listOf(plan[0].copy(status = Status.PENDING)), listOf(plan[0])))
        assertNull(TaskPeek.finished(emptyList(), advanced))
    }

    @Test fun onlyRowsThatChangedAreMarked() {
        assertEquals(setOf(0, 1, 3), TaskPeek.changed(plan, advanced + TodoItem("Ship")))
        assertEquals(emptySet<Int>(), TaskPeek.changed(emptyList(), plan))
    }
}
