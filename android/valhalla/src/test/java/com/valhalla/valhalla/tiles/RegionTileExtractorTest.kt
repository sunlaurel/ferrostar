package com.valhalla.valhalla.tiles

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RegionTileExtractorTest {

  @get:Rule val tmp = TemporaryFolder()

  /** Build a minimal ustar entry (header + NUL-padded data) sufficient for the extractor's reader. */
  private fun tarEntry(name: String, data: ByteArray): ByteArray {
    val header = ByteArray(512)
    val nameBytes = name.toByteArray(Charsets.US_ASCII)
    System.arraycopy(nameBytes, 0, header, 0, nameBytes.size)
    // size: 11 octal digits, zero-padded, then a NUL terminator (offset 124).
    val octal = java.lang.Long.toOctalString(data.size.toLong()).padStart(11, '0')
    val octalBytes = octal.toByteArray(Charsets.US_ASCII)
    System.arraycopy(octalBytes, 0, header, 124, octalBytes.size)
    header[124 + 11] = 0
    header[156] = '0'.code.toByte() // typeflag: regular file
    val paddedLen = ((data.size + 511) / 512) * 512
    return header + data + ByteArray(paddedLen - data.size)
  }

  private fun buildTar(entries: List<Pair<String, ByteArray>>): ByteArray {
    var out = ByteArray(0)
    for ((name, data) in entries) out += tarEntry(name, data)
    // Two zero blocks mark end-of-archive.
    return out + ByteArray(1024)
  }

  @Test
  fun extract_keepsOnlyTilesIntersectingBufferedBbox() {
    val sf = "tile-762486".toByteArray()
    val l1 = "tile-47701".toByteArray()
    val l0 = "tile-3015".toByteArray()
    val far = "tile-far".toByteArray()

    val tar = tmp.newFile("extract.tar")
    tar.writeBytes(
        buildTar(
            listOf(
                "2/000/762/486.gph" to sf, // lon[1.5,1.75] lat[42.25,42.5] -> keep
                "1/047/701.gph" to l1, //      lon[1,2]     lat[42,43]      -> keep
                "0/003/015.gph" to l0, //      lon[0,4]     lat[42,46]      -> keep
                "2/000/000/000.gph" to far, //  lon[-180,-179.75] far south -> drop
                "index.bin" to "not a tile".toByteArray(), //                 -> drop (non-.gph)
            )))

    val outDir = File(tmp.root, "offline_tiles")
    val bbox = GeoBounds(minLat = 42.4, minLon = 1.4, maxLat = 42.6, maxLon = 1.6)

    val result = RegionTileExtractor.extract(tar, bbox, outDir, bufferDegrees = 0.25)

    assertEquals(3, result.tilesCopied)

    val kept = File(outDir, "2/000/762/486.gph")
    assertTrue("L2 SF tile should be copied", kept.exists())
    assertArrayEquals("bytes must be preserved verbatim", sf, kept.readBytes())
    assertTrue(File(outDir, "1/047/701.gph").exists())
    assertTrue(File(outDir, "0/003/015.gph").exists())

    assertFalse("far tile must be dropped", File(outDir, "2/000/000/000.gph").exists())
    assertFalse("index.bin must be dropped", File(outDir, "index.bin").exists())
  }

  @Test
  fun extract_clearsStaleTilesFromPreviousRun() {
    val outDir = File(tmp.root, "offline_tiles")
    outDir.mkdirs()
    val stale = File(outDir, "2/999/999/999.gph")
    stale.parentFile?.mkdirs()
    stale.writeBytes("stale".toByteArray())

    val tar = tmp.newFile("extract2.tar")
    tar.writeBytes(buildTar(listOf("2/000/762/486.gph" to "x".toByteArray())))

    RegionTileExtractor.extract(
        tar,
        GeoBounds(minLat = 42.4, minLon = 1.4, maxLat = 42.6, maxLon = 1.6),
        outDir)

    assertFalse("stale tile from a prior run must be removed", stale.exists())
    assertTrue(File(outDir, "2/000/762/486.gph").exists())
  }

  @Test
  fun extract_handlesEntriesWithLeadingDotSlash() {
    val tar = tmp.newFile("extract3.tar")
    tar.writeBytes(
        buildTar(
            listOf(
                "./2/000/762/486.gph" to "x".toByteArray(),
                "./" to ByteArray(0), // root dir-style entry, no extension -> ignored
            )))

    val outDir = File(tmp.root, "offline_tiles")
    val result =
        RegionTileExtractor.extract(
            tar, GeoBounds(minLat = 42.4, minLon = 1.4, maxLat = 42.6, maxLon = 1.6), outDir)

    assertEquals(1, result.tilesCopied)
    // The ./ prefix must be stripped so the path round-trips to a GraphId under tile_dir.
    assertTrue(File(outDir, "2/000/762/486.gph").exists())
  }
}
