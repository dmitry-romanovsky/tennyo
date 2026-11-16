package app.tennyo.host.engine.agents

import app.tennyo.contract.ChatState
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AcpMappingTest {
    @Test
    fun `a mode setting replaces the legacy modes so the phone offers one mode picker`() {
        val setup = AcpJson.parseToJsonElement(
            """{"modes":{"currentModeId":"default","availableModes":[{"id":"default","name":"Ask"}]},
              "configOptions":[{"id":"mode","name":"Mode","category":"mode","type":"select","currentValue":"default",
                "options":[{"value":"default","name":"Ask"},{"value":"acceptEdits","name":"Accept edits"}]}]}""",
        ).jsonObject

        val state = ChatState().withSessionSetup(setup)

        assertTrue(state.modes.isEmpty())
        assertEquals(listOf("mode"), state.configOptions.map { it.id })
    }

    @Test
    fun `legacy modes stay when no setting covers them`() {
        val setup = AcpJson.parseToJsonElement(
            """{"modes":{"currentModeId":"ask","availableModes":[{"id":"ask","name":"Ask"}]},
              "configOptions":[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"fast",
                "options":[{"value":"fast","name":"Fast"}]}]}""",
        ).jsonObject

        val state = ChatState().withSessionSetup(setup)

        assertEquals(listOf("ask"), state.modes.map { it.id })
    }
}
