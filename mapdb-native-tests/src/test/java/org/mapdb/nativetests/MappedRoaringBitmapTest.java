// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.mapdb.collections.impl.RoaringU32;
import org.mapdb.collections.impl.mmap.MappedRoaringBitmap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 <b>B1 / memory-mapped feasibility probe</b>
 * ({@link MappedRoaringBitmap}): a lazy, directory-indexed, mmap-backed read view
 * over {@link RoaringU32}'s frozen canonical serialized form.
 *
 * <p>The core assertion is <b>parity with the in-RAM type</b> — for every bitmap
 * shape (empty, ARRAY-only chunks, dense BITMAP chunks, chunks spanning the full
 * unsigned {@code u32} range including high-bit-set / {@code i32}-negative values),
 * the mapped view's {@code contains} / {@code cardinality} / {@code min} / {@code
 * max} / iteration match {@link RoaringU32} exactly, and {@code toRoaringU32()}
 * round-trips <b>byte-for-byte</b>. Also covers the structural reader-MUST-reject
 * rules ({@code open} on corrupt images) and the closed-view guard.
 */
public class MappedRoaringBitmapTest
{
    @TempDir
    Path dir;

    private Path writeBitmap(String name, RoaringU32 bitmap) throws IOException
    {
        Path p = dir.resolve(name);
        long written = MappedRoaringBitmap.write(p, bitmap);
        assertEquals(bitmap.serialize().length, written, "write returns image length");
        return p;
    }

    /** Assert the mapped view is fully consistent with the in-RAM bitmap. */
    private void assertParity(RoaringU32 original, MappedRoaringBitmap view)
    {
        assertEquals(original.cardinality(), view.cardinality(), "cardinality");
        assertEquals(original.chunkCount(), view.chunkCount(), "chunkCount");
        assertEquals(original.isEmpty(), view.isEmpty(), "isEmpty");
        assertEquals(original.min(), view.min(), "min");
        assertEquals(original.max(), view.max(), "max");

        // Iteration order + membership: the mapped view reproduces toSortedArray().
        int[] expected = original.toSortedArray();
        List<Integer> got = new ArrayList<>();
        view.forEach(got::add);
        int[] gotArr = got.stream().mapToInt(Integer::intValue).toArray();
        assertArrayEquals(expected, gotArr, "forEach reproduces unsigned-ascending order");

        // Every member is contained.
        for (int v : expected)
        {
            assertTrue(view.contains(v), () -> "member missing from view");
        }

        // Byte-for-byte round-trip through the frozen deserializer.
        assertArrayEquals(original.serialize(), view.toRoaringU32().serialize(),
                "toRoaringU32 round-trips byte-identically");
        assertEquals(original.serialize().length, view.byteLength(), "byteLength == image length");
    }

    private static RoaringU32 of(int... vals)
    {
        RoaringU32 b = new RoaringU32();
        for (int v : vals)
        {
            b.add(v);
        }
        return b;
    }

    // ---- parity across shapes ---------------------------------------------

    @Test
    void emptyBitmapRoundTrips() throws IOException
    {
        RoaringU32 original = of();
        try (var ignored = new AutoCloseableView(writeBitmap("empty.rbm", original)))
        {
            MappedRoaringBitmap view = ignored.view;
            assertTrue(view.isEmpty());
            assertEquals(0, view.chunkCount());
            assertEquals(0L, view.cardinality());
            assertParity(original, view);
        }
    }

    @Test
    void singleArrayChunk() throws IOException
    {
        RoaringU32 original = of(1, 5, 9, 100, 65535);
        MappedRoaringBitmap view = MappedRoaringBitmap.open(writeBitmap("array.rbm", original));
        assertParity(original, view);
        assertTrue(view.contains(5));
        assertFalse(view.contains(6));
        view.close();
    }

    @Test
    void multipleChunksAcrossHighKeys() throws IOException
    {
        // values in distinct high-key chunks, including high-bit-set (i32-negative) u32.
        RoaringU32 original = of(1, 0x0001_0002, 0x0002_0003, 0x8000_0000, 0xFFFF_FFFF, -2, 7);
        MappedRoaringBitmap view = MappedRoaringBitmap.open(writeBitmap("multi.rbm", original));
        assertParity(original, view);
        // unsigned order: 1,7 (chunk 0) then higher chunks; 0xFFFFFFFF (== -1) is the max.
        assertEquals(1, view.min().getAsInt());
        assertEquals(0xFFFF_FFFF, view.max().getAsInt());
        assertTrue(view.contains(0x8000_0000));
        assertTrue(view.contains(-1)); // 0xFFFFFFFF
        assertFalse(view.contains(0x8000_0001));
        view.close();
    }

    @Test
    void denseBitmapContainerChunk() throws IOException
    {
        // > 4096 values in one high-key chunk forces a BITMAP container.
        RoaringU32 original = new RoaringU32();
        for (int i = 0; i < 5000; i++)
        {
            original.add(0x00AB_0000 + i * 3); // all share high key 0x00AB, spread lows
        }
        assertArrayEquals(new String[] {"bitmap"}, original.containerTypes(),
                "5000 in one chunk must be a BITMAP");
        MappedRoaringBitmap view = MappedRoaringBitmap.open(writeBitmap("bitmap.rbm", original));
        assertParity(original, view);
        assertTrue(view.contains(0x00AB_0000));
        assertTrue(view.contains(0x00AB_0000 + 4999 * 3));
        assertFalse(view.contains(0x00AB_0000 + 1)); // stride 3, so +1 is absent
        view.close();
    }

    @Test
    void mixedArrayAndBitmapChunks() throws IOException
    {
        RoaringU32 original = new RoaringU32();
        original.add(3);                       // chunk 0x0000 -> tiny ARRAY
        original.add(9);
        for (int i = 0; i < 6000; i++)         // chunk 0x0005 -> BITMAP
        {
            original.add(0x0005_0000 + i);
        }
        original.add(0x000A_1234);             // chunk 0x000A -> ARRAY
        MappedRoaringBitmap view = MappedRoaringBitmap.open(writeBitmap("mixed.rbm", original));
        assertParity(original, view);
        view.close();
    }

    @Test
    void randomizedParitySweep() throws IOException
    {
        Random rnd = new Random(0xB1_C0FFEEL);
        for (int trial = 0; trial < 8; trial++)
        {
            RoaringU32 original = new RoaringU32();
            int n = rnd.nextInt(20000);
            for (int i = 0; i < n; i++)
            {
                original.add(rnd.nextInt()); // full i32 range, bit-reinterpreted to u32
            }
            MappedRoaringBitmap view = MappedRoaringBitmap.open(
                    writeBitmap("rnd" + trial + ".rbm", original));
            assertParity(original, view);
            // probe membership of non-members too
            for (int i = 0; i < 500; i++)
            {
                int v = rnd.nextInt();
                assertEquals(original.contains(v), view.contains(v), "contains parity on probe");
            }
            view.close();
        }
    }

    // ---- structural rejection (reader-MUST-reject) -------------------------

    @Test
    void rejectsBadMagic() throws IOException
    {
        byte[] image = of(1, 2, 3).serialize();
        image[0] ^= 0xFF;
        Path p = dir.resolve("badmagic.rbm");
        Files.write(p, image);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsBadVersion() throws IOException
    {
        byte[] image = of(1, 2, 3).serialize();
        image[4] = 0x02; // VERSION low byte -> 2
        Path p = dir.resolve("badversion.rbm");
        Files.write(p, image);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsNonZeroReserved() throws IOException
    {
        byte[] image = of(1, 2, 3).serialize();
        image[6] = 0x01; // RESERVED
        Path p = dir.resolve("badreserved.rbm");
        Files.write(p, image);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsTruncatedHeader() throws IOException
    {
        Path p = dir.resolve("shorthdr.rbm");
        Files.write(p, new byte[] {0x55, 0x30, 0x52}); // 3 bytes, < 12
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsTruncatedPayload() throws IOException
    {
        byte[] image = of(1, 5, 9).serialize();
        byte[] chopped = new byte[image.length - 2]; // drop a low key from the ARRAY payload
        System.arraycopy(image, 0, chopped, 0, chopped.length);
        Path p = dir.resolve("truncpayload.rbm");
        Files.write(p, chopped);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsTrailingBytes() throws IOException
    {
        byte[] image = of(1, 5, 9).serialize();
        byte[] extended = new byte[image.length + 4];
        System.arraycopy(image, 0, extended, 0, image.length);
        Path p = dir.resolve("trailing.rbm");
        Files.write(p, extended);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsUnknownContainerTag() throws IOException
    {
        byte[] image = of(1, 5, 9).serialize();
        image[HEADER_PLUS_TAG] = 0x07; // corrupt TAG (offset 12 high + 2 = 14)
        Path p = dir.resolve("badtag.rbm");
        Files.write(p, image);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    private static final int HEADER_PLUS_TAG = 12 + 2; // header(12) + HIGH(2) -> TAG offset

    // ---- deep-corrupt payloads (structurally valid, deferred checks) -------

    @Test
    void corruptBitmapNoSetBitRejectsNotCrashes() throws IOException
    {
        // BITMAP declared card=4097 but all-zero payload: structurally valid (open
        // succeeds — the popcount check is deferred), but min()/max() must reject via
        // IllegalArgumentException, NOT crash with IllegalStateException. And the
        // eager toRoaringU32() must reject it (popcount 0 != 4097).
        byte[] img = image(1, concat(
                chunkHeader(0x0000, 2, 0, 4096),  // TAG_BITMAP, card = 4096+1 = 4097
                new byte[BITMAP_PAYLOAD_BYTES]));  // all zero words
        Path p = dir.resolve("zerobitmap.rbm");
        Files.write(p, img);
        MappedRoaringBitmap view = MappedRoaringBitmap.open(p); // structurally valid
        assertThrows(IllegalArgumentException.class, view::min);
        assertThrows(IllegalArgumentException.class, view::max);
        assertThrows(IllegalArgumentException.class, view::toRoaringU32);
        view.close();
    }

    @Test
    void toRoaringU32RejectsNonAscendingArrayPayload() throws IOException
    {
        // ARRAY card=2 with descending low keys [2,1]: open() accepts (intra-array
        // ordering is a deferred deep check), but the eager frozen deserializer rejects.
        byte[] img = image(1, concat(
                chunkHeader(0x0000, 1, 0, 1),      // TAG_ARRAY, card = 1+1 = 2
                le16(2), le16(1)));                 // non-ascending low keys
        Path p = dir.resolve("unsortedarray.rbm");
        Files.write(p, img);
        MappedRoaringBitmap view = MappedRoaringBitmap.open(p); // structurally valid
        assertThrows(IllegalArgumentException.class, view::toRoaringU32);
        view.close();
    }

    // ---- more structural rejects (reader-MUST-reject) ----------------------

    @Test
    void rejectsChunkCountOverMax() throws IOException
    {
        byte[] hdr = header(65537); // > 65536
        Path p = dir.resolve("bigcount.rbm");
        Files.write(p, hdr);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsNonAscendingHighKeys() throws IOException
    {
        byte[] img = image(2,
                concat(chunkHeader(0x0002, 1, 0, 0), le16(0)),
                concat(chunkHeader(0x0001, 1, 0, 0), le16(0))); // high 1 after 2
        Path p = dir.resolve("nonasc.rbm");
        Files.write(p, img);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsDuplicateHighKeys() throws IOException
    {
        byte[] img = image(2,
                concat(chunkHeader(0x0001, 1, 0, 0), le16(0)),
                concat(chunkHeader(0x0001, 1, 0, 0), le16(0))); // duplicate high 1
        Path p = dir.resolve("duphigh.rbm");
        Files.write(p, img);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsNonZeroPad() throws IOException
    {
        byte[] image = of(1).serialize();
        image[15] = 0x01; // PAD byte: header(12) + HIGH(2) + TAG(1) -> offset 15
        Path p = dir.resolve("badpad.rbm");
        Files.write(p, image);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsArrayCardinalityOverBand() throws IOException
    {
        // TAG_ARRAY with card = 4097 (> 4096) is non-canonical.
        byte[] img = image(1, concat(
                chunkHeader(0x0000, 1, 0, 4096),   // card = 4097
                new byte[4097 * 2]));
        Path p = dir.resolve("arrayband.rbm");
        Files.write(p, img);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsBitmapCardinalityUnderBand() throws IOException
    {
        // TAG_BITMAP with card = 4096 (<= 4096) is non-canonical.
        byte[] img = image(1, concat(
                chunkHeader(0x0000, 2, 0, 4095),   // card = 4096
                new byte[BITMAP_PAYLOAD_BYTES]));
        Path p = dir.resolve("bitmapband.rbm");
        Files.write(p, img);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    @Test
    void rejectsTruncatedChunkHeader() throws IOException
    {
        byte[] hdr = header(1);
        byte[] img = new byte[hdr.length + 3]; // claims a chunk but only 3 bytes follow
        System.arraycopy(hdr, 0, img, 0, hdr.length);
        Path p = dir.resolve("trunchdr.rbm");
        Files.write(p, img);
        assertThrows(IllegalArgumentException.class, () -> MappedRoaringBitmap.open(p));
    }

    // ---- lifecycle guard ---------------------------------------------------

    @Test
    void closedViewRejectsQueries() throws IOException
    {
        MappedRoaringBitmap view = MappedRoaringBitmap.open(writeBitmap("closeme.rbm", of(1, 2, 3)));
        view.close();
        assertThrows(IllegalStateException.class, () -> view.contains(1));
        assertThrows(IllegalStateException.class, view::cardinality);
        assertThrows(IllegalStateException.class, view::chunkCount);
        assertThrows(IllegalStateException.class, view::isEmpty);
        assertThrows(IllegalStateException.class, view::min);
        assertThrows(IllegalStateException.class, view::max);
        assertThrows(IllegalStateException.class, view::byteLength);
        assertThrows(IllegalStateException.class, view::toRoaringU32);
        assertThrows(IllegalStateException.class, () -> view.forEach(v -> { }));
    }

    // ---- raw-image builders (little-endian, mirror the frozen layout) ------

    private static final int BITMAP_PAYLOAD_BYTES = 1024 * 8;

    private static byte[] header(long chunkCount)
    {
        ByteBuffer b = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0x32523055); // MAGIC
        b.putShort((short) 1); // VERSION
        b.putShort((short) 0); // RESERVED
        b.putInt((int) chunkCount);
        return b.array();
    }

    private static byte[] chunkHeader(int high, int tag, int pad, int cardMinus1)
    {
        ByteBuffer b = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) high);
        b.put((byte) tag);
        b.put((byte) pad);
        b.putShort((short) cardMinus1);
        return b.array();
    }

    private static byte[] le16(int v)
    {
        return new byte[] {(byte) (v & 0xFF), (byte) ((v >>> 8) & 0xFF)};
    }

    private static byte[] concat(byte[]... parts)
    {
        int len = 0;
        for (byte[] p : parts)
        {
            len += p.length;
        }
        byte[] out = new byte[len];
        int pos = 0;
        for (byte[] p : parts)
        {
            System.arraycopy(p, 0, out, pos, p.length);
            pos += p.length;
        }
        return out;
    }

    private static byte[] image(long chunkCount, byte[]... chunks)
    {
        return concat(header(chunkCount), concat(chunks));
    }

    /** Small try-with-resources helper so the empty-case test reads cleanly. */
    private static final class AutoCloseableView implements AutoCloseable
    {
        final MappedRoaringBitmap view;

        AutoCloseableView(Path p) throws IOException
        {
            this.view = MappedRoaringBitmap.open(p);
        }

        @Override
        public void close()
        {
            view.close();
        }
    }
}
