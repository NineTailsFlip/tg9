package dev.tg9.media

import dev.tg9.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewingTest {
    private val eps = 0.001f

    @Test
    fun aPictureFitsCentred() {
        // 1000×500 into 200×200: fits the width, centred vertically.
        val p = place(1000f, 500f, 200f, 200f, zoomed = false, zoom = 2.5f, panX = 30f, panY = 30f)!!
        assertEquals(0.2f, p.scale, eps)
        assertEquals(0f, p.left, eps)
        assertEquals(50f, p.top, eps)
        // Nothing off screen: nothing to pan.
        assertEquals(0f, p.panX, eps)
        assertEquals(0f, p.panY, eps)
    }

    @Test
    fun zoomedInThePanStopsAtTheEdges() {
        // 400×400 into 200×200 at ×2: 400×400 on screen, 100 off each side.
        val p = place(400f, 400f, 200f, 200f, zoomed = true, zoom = 2f, panX = 500f, panY = -40f)!!
        assertEquals(1f, p.scale, eps)
        assertEquals(100f, p.panX, eps)
        assertEquals(-40f, p.panY, eps)
        assertEquals(0f, p.left, eps)
        assertEquals(-140f, p.top, eps)
    }

    @Test
    fun noPlacementBeforeTheSizesAreKnown() {
        assertNull(place(0f, 100f, 200f, 200f, false, 2f, 0f, 0f))
        assertNull(place(100f, 100f, 0f, 200f, false, 2f, 0f, 0f))
    }

    @Test
    fun picturesDecodeToAboutTheTarget() {
        assertEquals(1, decodeSampleSize(800, 600, 960))
        assertEquals(1, decodeSampleSize(1919, 1000, 960))
        assertEquals(2, decodeSampleSize(1920, 1000, 960))
        // A 12 MP camera photo for a 480×800 screen (target 1600).
        assertEquals(2, decodeSampleSize(4000, 3000, 1600))
        assertEquals(4, decodeSampleSize(8000, 6000, 1600))
    }

    @Test
    fun theNeighbourIsTheNearestByIdEitherWay() {
        val found = arrayOf(5L, 9L, 10L, 12L, 20L).map { Fixtures.message(it, Fixtures.text(""), 0) }.toTypedArray()
        assertEquals(9L, neighbour(found, 10, -1)?.id)
        assertEquals(12L, neighbour(found, 10, +1)?.id)
        // Whether or not the current one is among them.
        assertEquals(12L, neighbour(found, 11, +1)?.id)
        assertNull(neighbour(found, 5, -1))
        assertNull(neighbour(found, 20, +1))
    }
}
