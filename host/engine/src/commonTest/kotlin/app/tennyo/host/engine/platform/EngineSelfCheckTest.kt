package app.tennyo.host.engine.platform

import kotlin.test.Test
import kotlin.test.assertNull

class EngineSelfCheckTest {
    @Test
    fun `core self check passes on this platform`() {
        assertNull(EngineSelfCheck.run())
    }
}
