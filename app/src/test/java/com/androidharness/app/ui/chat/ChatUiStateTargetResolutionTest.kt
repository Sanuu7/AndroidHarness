package com.androidharness.app.ui.chat

import com.androidharness.app.agent.AgentMode
import com.androidharness.app.llm.ProviderConfig
import com.androidharness.app.llm.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatUiStateTargetResolutionTest {

    private val providerA = ProviderConfig(
        id = "provider-a",
        name = "Provider A",
        type = ProviderType.OPENAI_COMPAT,
        baseUrl = "https://api.openai.com/v1",
        model = "model-a-default",
    )

    private val providerB = ProviderConfig(
        id = "provider-b",
        name = "Provider B",
        type = ProviderType.ANTHROPIC,
        baseUrl = "https://api.anthropic.com/v1",
        model = "model-b-default",
    )

    @Test
    fun `model selection target defaults to active when dual planning is off`() {
        val state = ChatUiState(
            dualPlanning = false,
            mode = AgentMode.PLAN,
            activeProviderId = providerA.id,
            activeModel = "active-override",
            planningProviderId = providerB.id,
            planningModel = "plan-override",
            providers = listOf(providerA, providerB),
        )

        assertEquals(ModelSelectionTarget.ACTIVE, state.modelSelectionTarget)
        assertEquals(providerA.id, state.selectedProviderIdFor(ModelSelectionTarget.ACTIVE))
        assertEquals("active-override", state.selectedModelFor(ModelSelectionTarget.ACTIVE))
    }

    @Test
    fun `model selection target selects planning when dual planning is in plan mode`() {
        val state = ChatUiState(
            dualPlanning = true,
            mode = AgentMode.PLAN,
            activeProviderId = providerA.id,
            activeModel = "active-override",
            planningProviderId = providerB.id,
            planningModel = "plan-override",
            providers = listOf(providerA, providerB),
        )

        assertEquals(ModelSelectionTarget.PLANNING, state.modelSelectionTarget)
        assertEquals(providerB.id, state.selectedProviderIdFor(ModelSelectionTarget.PLANNING))
        assertEquals("plan-override", state.selectedModelFor(ModelSelectionTarget.PLANNING))
    }

    @Test
    fun `model selection target selects execution when dual planning is in act mode`() {
        val state = ChatUiState(
            dualPlanning = true,
            mode = AgentMode.ACT,
            activeProviderId = providerA.id,
            activeModel = "active-override",
            executionProviderId = providerB.id,
            executionModel = null,
            providers = listOf(providerA, providerB),
        )

        assertEquals(ModelSelectionTarget.EXECUTION, state.modelSelectionTarget)
        assertEquals(providerB.id, state.selectedProviderIdFor(ModelSelectionTarget.EXECUTION))
        assertEquals("model-b-default", state.selectedModelFor(ModelSelectionTarget.EXECUTION))
    }

    @Test
    fun `role targets fall back to active provider when role provider is unset`() {
        val state = ChatUiState(
            dualPlanning = true,
            mode = AgentMode.PLAN,
            activeProviderId = providerA.id,
            activeModel = null,
            planningProviderId = null,
            planningModel = null,
            providers = listOf(providerA),
        )

        assertEquals(ModelSelectionTarget.PLANNING, state.modelSelectionTarget)
        assertEquals(providerA.id, state.selectedProviderIdFor(ModelSelectionTarget.PLANNING))
        assertEquals("model-a-default", state.selectedModelFor(ModelSelectionTarget.PLANNING))
    }

    @Test
    fun `empty provider catalog yields null models safely`() {
        val state = ChatUiState(
            dualPlanning = false,
            mode = AgentMode.ACT,
            activeProviderId = null,
            activeModel = null,
            providers = emptyList(),
        )

        assertNull(state.selectedProviderIdFor(ModelSelectionTarget.ACTIVE))
        assertNull(state.selectedModelFor(ModelSelectionTarget.ACTIVE))
    }
}
