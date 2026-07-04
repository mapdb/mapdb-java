// Copyright (c) 2026 Jan Kotek.
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See ../LICENSE-EPL-1.0.txt and ../LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.mapdb.collections.impl.RoaringU32;
import org.mapdb.collections.impl.digest.CollectionDigest;
import org.mapdb.collections.impl.digest.CollectionDigest.MerkleProof;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.sorted.ImmutableSortedSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native battery for the archeology-2 "A2 — digest half" content digest
 * ({@link CollectionDigest}). Exercises determinism / logical-equality, the
 * sensitivity axes (member, value, order, type, count), empty / singleton
 * distinctness, the combiner's asymmetry (value-transpose maps differ),
 * Merkle-tree shapes across odd carries (sizes 1..17), and inclusion-proof
 * soundness (every member verifies; wrong member / value / digest / count are
 * rejected).
 */
public class CollectionDigestTest
{
    private static ImmutableSortedSet<Integer> set(int... vals)
    {
        return ImmutableSortedSet.fromSorted(vals);
    }

    private static ImmutableSortedMap<Integer, Integer> map(int[] keys, int[] vals)
    {
        return ImmutableSortedMap.fromSorted(keys, vals);
    }

    private static RoaringU32 roaring(int... vals)
    {
        RoaringU32 s = new RoaringU32();
        for (int v : vals)
        {
            s.add(v);
        }
        return s;
    }

    /** 0..n-1 as a strictly ascending int[]. */
    private static int[] iota(int n)
    {
        int[] a = new int[n];
        for (int i = 0; i < n; i++)
        {
            a[i] = i;
        }
        return a;
    }

    // ---- Determinism / logical equality ----------------------------------

    @Test
    public void sameSetSameDigest()
    {
        assertEquals(CollectionDigest.ofSortedSet(set(1, 2, 3, 4, 5)),
                CollectionDigest.ofSortedSet(set(1, 2, 3, 4, 5)));
    }

    @Test
    public void sameMapSameDigest()
    {
        int[] k = {1, 3, 5};
        int[] v = {10, 30, 50};
        assertEquals(CollectionDigest.ofSortedMap(map(k, v)),
                CollectionDigest.ofSortedMap(map(k, v)));
    }

    @Test
    public void roaringLogicalEqualityIndependentOfInsertOrder()
    {
        // Same members, different add order and container spread → equal digest.
        assertEquals(CollectionDigest.ofRoaring(roaring(70000, 1, 200000, 140000)),
                CollectionDigest.ofRoaring(roaring(1, 140000, 70000, 200000)));
    }

    // ---- Sensitivity axes -------------------------------------------------

    @Test
    public void oneDifferentMemberChangesSetDigest()
    {
        assertNotEquals(CollectionDigest.ofSortedSet(set(1, 2, 3)),
                CollectionDigest.ofSortedSet(set(1, 2, 4)));
    }

    @Test
    public void oneDifferentValueChangesMapDigest()
    {
        assertNotEquals(CollectionDigest.ofSortedMap(map(new int[] {1, 2}, new int[] {9, 9})),
                CollectionDigest.ofSortedMap(map(new int[] {1, 2}, new int[] {9, 8})));
    }

    @Test
    public void mapKeysMatterNotJustValues()
    {
        assertNotEquals(CollectionDigest.ofSortedMap(map(new int[] {1, 2}, new int[] {5, 6})),
                CollectionDigest.ofSortedMap(map(new int[] {1, 3}, new int[] {5, 6})));
    }

    @Test
    public void transposedMapValuesDiffer()
    {
        // Same key set, values swapped between keys — must differ (proves the
        // entry leaf / node combiner is not symmetric in (key,value)).
        long a = CollectionDigest.ofSortedMap(map(new int[] {1, 3}, new int[] {2, 4}));
        long b = CollectionDigest.ofSortedMap(map(new int[] {1, 3}, new int[] {4, 2}));
        assertNotEquals(a, b);
    }

    @Test
    public void keyValueSwapWithinEntryDiffers()
    {
        // {7:8} vs {8:7} — same two i32s, opposite roles.
        assertNotEquals(CollectionDigest.ofSortedMap(map(new int[] {7}, new int[] {8})),
                CollectionDigest.ofSortedMap(map(new int[] {8}, new int[] {7})));
    }

    // ---- Type separation --------------------------------------------------

    @Test
    public void setAndMapWithSameKeysDiffer()
    {
        assertNotEquals(CollectionDigest.ofSortedSet(set(1, 2, 3)),
                CollectionDigest.ofSortedMap(map(new int[] {1, 2, 3}, new int[] {1, 2, 3})));
    }

    @Test
    public void roaringAndSortedSetSameMembersDiffer()
    {
        // Different collection TYPES → different digests by design.
        assertNotEquals(CollectionDigest.ofRoaring(roaring(1, 2, 3)),
                CollectionDigest.ofSortedSet(set(1, 2, 3)));
    }

    @Test
    public void emptyCollectionsAllDistinctByType()
    {
        long emptySet = CollectionDigest.ofSortedSet(set());
        long emptyMap = CollectionDigest.ofSortedMap(map(new int[] {}, new int[] {}));
        long emptyRoaring = CollectionDigest.ofRoaring(roaring());
        assertNotEquals(emptySet, emptyMap);
        assertNotEquals(emptySet, emptyRoaring);
        assertNotEquals(emptyMap, emptyRoaring);
    }

    // ---- Count / structure ------------------------------------------------

    @Test
    public void emptyDiffersFromSingleton()
    {
        assertNotEquals(CollectionDigest.ofSortedSet(set()),
                CollectionDigest.ofSortedSet(set(0)));
    }

    @Test
    public void singletonDiffersByMember()
    {
        assertNotEquals(CollectionDigest.ofSortedSet(set(0)),
                CollectionDigest.ofSortedSet(set(1)));
    }

    @Test
    public void unsignedRoaringMemberDistinct()
    {
        // -1 is the max u32 member; must be a distinct member, not confused with anything.
        assertNotEquals(CollectionDigest.ofRoaring(roaring(0, -1)),
                CollectionDigest.ofRoaring(roaring(0, 1)));
    }

    @Test
    public void manyDistinctSizesAllDistinctDigests()
    {
        // Prefixes 0..16 elements — count binding + tree shape keep them distinct.
        List<Long> seen = new ArrayList<>();
        for (int n = 0; n <= 16; n++)
        {
            long d = CollectionDigest.ofSortedSet(set(iota(n)));
            assertFalse(seen.contains(d), "digest collision at n=" + n);
            seen.add(d);
        }
    }

    // ---- Inclusion proofs: soundness across tree shapes ------------------

    @Test
    public void everyMemberProvesAndVerifiesAcrossShapes()
    {
        // Sizes chosen to exercise odd carries at multiple levels: 1,2,3,5,8,9,17.
        for (int n : new int[] {1, 2, 3, 5, 8, 9, 17})
        {
            ImmutableSortedSet<Integer> s = set(iota(n));
            long digest = CollectionDigest.ofSortedSet(s);
            for (int e = 0; e < n; e++)
            {
                MerkleProof p = CollectionDigest.proveMember(s, e);
                assertTrue(CollectionDigest.verifyMember(digest, e, p),
                        "member " + e + " of " + n + " should verify");
            }
        }
    }

    @Test
    public void proofRejectsWrongMember()
    {
        ImmutableSortedSet<Integer> s = set(iota(9));
        long digest = CollectionDigest.ofSortedSet(s);
        MerkleProof p = CollectionDigest.proveMember(s, 4);
        // A proof for member 4 must not verify member 5 (a real, but different, member).
        assertFalse(CollectionDigest.verifyMember(digest, 5, p));
        // ...nor a non-member.
        assertFalse(CollectionDigest.verifyMember(digest, 100, p));
    }

    @Test
    public void proofRejectsWrongDigest()
    {
        ImmutableSortedSet<Integer> s = set(iota(9));
        MerkleProof p = CollectionDigest.proveMember(s, 4);
        long otherDigest = CollectionDigest.ofSortedSet(set(iota(10)));
        assertFalse(CollectionDigest.verifyMember(otherDigest, 4, p));
    }

    @Test
    public void singletonProofHasZeroHeightAndVerifies()
    {
        ImmutableSortedSet<Integer> s = set(42);
        long digest = CollectionDigest.ofSortedSet(s);
        MerkleProof p = CollectionDigest.proveMember(s, 42);
        assertEquals(0, p.height());
        assertEquals(1L, p.count());
        assertTrue(CollectionDigest.verifyMember(digest, 42, p));
    }

    @Test
    public void proveMemberAbsentThrows()
    {
        ImmutableSortedSet<Integer> s = set(1, 2, 3);
        assertThrows(IllegalArgumentException.class, () -> CollectionDigest.proveMember(s, 99));
    }

    // ---- Entry inclusion proofs ------------------------------------------

    @Test
    public void everyEntryProvesAndVerifies()
    {
        int[] k = {10, 20, 30, 40, 50, 60, 70};
        int[] v = {1, 2, 3, 4, 5, 6, 7};
        ImmutableSortedMap<Integer, Integer> m = map(k, v);
        long digest = CollectionDigest.ofSortedMap(m);
        for (int i = 0; i < k.length; i++)
        {
            MerkleProof p = CollectionDigest.proveEntry(m, k[i]);
            assertTrue(CollectionDigest.verifyEntry(digest, k[i], v[i], p),
                    "entry " + k[i] + "->" + v[i] + " should verify");
        }
    }

    @Test
    public void entryProofRejectsWrongValue()
    {
        int[] k = {10, 20, 30};
        int[] v = {1, 2, 3};
        ImmutableSortedMap<Integer, Integer> m = map(k, v);
        long digest = CollectionDigest.ofSortedMap(m);
        MerkleProof p = CollectionDigest.proveEntry(m, 20);
        assertTrue(CollectionDigest.verifyEntry(digest, 20, 2, p));
        // Right key, wrong value → rejected.
        assertFalse(CollectionDigest.verifyEntry(digest, 20, 99, p));
    }

    @Test
    public void entryProofRejectsWrongKey()
    {
        int[] k = {10, 20, 30};
        int[] v = {1, 2, 3};
        ImmutableSortedMap<Integer, Integer> m = map(k, v);
        long digest = CollectionDigest.ofSortedMap(m);
        MerkleProof p = CollectionDigest.proveEntry(m, 20);
        // Proof for key 20 must not verify a different key even with a real value.
        assertFalse(CollectionDigest.verifyEntry(digest, 30, 3, p));
    }

    @Test
    public void proveEntryAbsentThrows()
    {
        ImmutableSortedMap<Integer, Integer> m = map(new int[] {1, 2}, new int[] {1, 2});
        assertThrows(IllegalArgumentException.class, () -> CollectionDigest.proveEntry(m, 99));
    }

    // ---- Larger differential ---------------------------------------------

    @Test
    public void largeSetRebuildEqualRemoveDiffersAllMembersProvable()
    {
        int n = 500;
        int[] a = new int[n];
        for (int i = 0; i < n; i++)
        {
            a[i] = i * 3 - 200; // spans negatives, strictly ascending
        }
        ImmutableSortedSet<Integer> s = set(a);
        long d = CollectionDigest.ofSortedSet(s);
        assertEquals(d, CollectionDigest.ofSortedSet(set(a)));

        int[] b = new int[n - 1]; // drop the middle element
        int j = 0;
        for (int i = 0; i < n; i++)
        {
            if (i == n / 2)
            {
                continue;
            }
            b[j++] = a[i];
        }
        assertNotEquals(d, CollectionDigest.ofSortedSet(set(b)));

        // Every member is provable and verifies against the full digest.
        for (int i = 0; i < n; i++)
        {
            MerkleProof p = CollectionDigest.proveMember(s, a[i]);
            assertTrue(CollectionDigest.verifyMember(d, a[i], p));
        }
    }
}
