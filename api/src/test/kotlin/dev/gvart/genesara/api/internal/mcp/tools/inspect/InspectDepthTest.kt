package dev.gvart.genesara.api.internal.mcp.tools.inspect

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class InspectDepthTest {

    @Test
    fun `low Perception lands in SHALLOW`() {
        assertEquals(InspectDepth.SHALLOW, inspectDepthFor(1))
        assertEquals(InspectDepth.SHALLOW, inspectDepthFor(4))
    }

    @Test
    fun `mid Perception lands in DETAILED`() {
        assertEquals(InspectDepth.DETAILED, inspectDepthFor(5))
        assertEquals(InspectDepth.DETAILED, inspectDepthFor(10))
        assertEquals(InspectDepth.DETAILED, inspectDepthFor(14))
    }

    @Test
    fun `high Perception lands in EXPERT`() {
        assertEquals(InspectDepth.EXPERT, inspectDepthFor(15))
        assertEquals(InspectDepth.EXPERT, inspectDepthFor(50))
    }
}
