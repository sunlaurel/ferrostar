package com.valhalla.valhalla.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TileGridTest {

  // Values verified against real Valhalla fixture tiles (Andorra, ~42.5N 1.5E).

  @Test
  fun level2_tileBounds_matchFixture() {
    // 2/000/762/486.gph
    val bounds = TileGrid.boundsOf(Tile(level = 2, tileId = 762486))!!
    assertEquals(42.25, bounds.minLat, 1e-9)
    assertEquals(1.5, bounds.minLon, 1e-9)
    assertEquals(42.5, bounds.maxLat, 1e-9)
    assertEquals(1.75, bounds.maxLon, 1e-9)
  }

  @Test
  fun level1_tileBounds_matchFixture() {
    // 1/047/701.gph
    val bounds = TileGrid.boundsOf(Tile(level = 1, tileId = 47701))!!
    assertEquals(42.0, bounds.minLat, 1e-9)
    assertEquals(1.0, bounds.minLon, 1e-9)
    assertEquals(43.0, bounds.maxLat, 1e-9)
    assertEquals(2.0, bounds.maxLon, 1e-9)
  }

  @Test
  fun level0_tileBounds_matchFixture() {
    // 0/003/015.gph
    val bounds = TileGrid.boundsOf(Tile(level = 0, tileId = 3015))!!
    assertEquals(42.0, bounds.minLat, 1e-9)
    assertEquals(0.0, bounds.minLon, 1e-9)
    assertEquals(46.0, bounds.maxLat, 1e-9)
    assertEquals(4.0, bounds.maxLon, 1e-9)
  }

  @Test
  fun tileFromPath_parsesAllLevels_andStripsPrefixes() {
    assertEquals(Tile(2, 762486), TileGrid.tileFromPath("2/000/762/486.gph"))
    assertEquals(Tile(2, 762486), TileGrid.tileFromPath("./2/000/762/486.gph"))
    assertEquals(Tile(1, 47701), TileGrid.tileFromPath("1/047/701.gph"))
    assertEquals(Tile(0, 3015), TileGrid.tileFromPath("0/003/015.gph"))
  }

  @Test
  fun tileFromPath_rejectsNonTileEntries() {
    assertNull(TileGrid.tileFromPath("index.bin"))
    assertNull(TileGrid.tileFromPath("2/000/762/")) // directory entry
    assertNull(TileGrid.tileFromPath("3/000/762/486.gph")) // transit level, not in routing extract
    assertNull(TileGrid.tileFromPath("./"))
  }

  @Test
  fun roundTrip_pathToBounds() {
    val tile = TileGrid.tileFromPath("2/000/763/926.gph")!!
    val bounds = TileGrid.boundsOf(tile)!!
    // Sanity: should sit just east of 762486, still over Andorra.
    assertTrue(bounds.minLon >= 1.5)
    assertTrue(bounds.minLat in 42.0..43.0)
  }

  @Test
  fun bounds_intersects_and_buffered() {
    val tile = GeoBounds(minLat = 42.25, minLon = 1.5, maxLat = 42.5, maxLon = 1.75)
    val overlapping = GeoBounds(minLat = 42.4, minLon = 1.6, maxLat = 42.6, maxLon = 1.9)
    val disjoint = GeoBounds(minLat = 40.0, minLon = 0.0, maxLat = 41.0, maxLon = 0.5)
    assertTrue(tile.intersects(overlapping))
    assertFalse(tile.intersects(disjoint))
    // Buffering the disjoint box outward enough should make it touch the tile.
    assertTrue(tile.intersects(disjoint.buffered(2.0)))
  }
}
