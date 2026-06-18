// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.nativetests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;
import org.mapdb.collections.impl.navigable.NavigableTreeMap;
import org.mapdb.collections.impl.navigable.NavigableTreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Native tests for the order-statistics rank / select carve-out (spec
 * {@code features/rank-select.md}) on the boxed-tree wrappers
 * {@link NavigableTreeMap} / {@link NavigableTreeSet}. These pin the per-port
 * obligations the shared scenario suite cannot reach cross-language: negative
 * index -> absence without trapping, the {@code selectEntry} value form, and the
 * round-trip identity {@code select(rank(k))==k} / {@code rank(select(i))==i}
 * over randomized insert/remove sequences (the Java carve-out's analogue of the
 * native ports' subtree-size invariant — here the live size and ordered scan
 * must stay consistent through remove/transplant).
 */
public class RankSelectTest
{
    private static NavigableTreeMap<Integer, Integer> mapOf(int... keys)
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.newMap();
        for (int k : keys)
        {
            m.put(k, k * 10);
        }
        return m;
    }

    private static NavigableTreeSet<Integer> setOf(int... els)
    {
        NavigableTreeSet<Integer> s = NavigableTreeSet.newSet();
        for (int e : els)
        {
            s.add(e);
        }
        return s;
    }

    // ---- rank: present, absent, signed ------------------------------------

    @Test
    public void mapRankPresentAbsentSigned()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30, 40, 50);
        // present keys -> 0-based index
        assertEquals(0, m.rank(10));
        assertEquals(2, m.rank(30));
        assertEquals(4, m.rank(50));
        // absent keys -> lower-bound index
        assertEquals(0, m.rank(5));
        assertEquals(2, m.rank(25));
        assertEquals(5, m.rank(55)); // past end -> size

        NavigableTreeMap<Integer, Integer> signed =
                mapOf(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE);
        assertEquals(0, signed.rank(Integer.MIN_VALUE));
        assertEquals(2, signed.rank(0));
        assertEquals(4, signed.rank(Integer.MAX_VALUE));
        // a key above MAX (none exists here) would be size; MIN never traps.
        assertEquals(Optional.of(Integer.MIN_VALUE), signed.selectKey(0));
        assertEquals(Optional.of(Integer.MAX_VALUE), signed.selectKey(4));
    }

    @Test
    public void setRankPresentAbsentSigned()
    {
        NavigableTreeSet<Integer> s = setOf(10, 20, 30, 40, 50);
        assertEquals(0, s.rank(10));
        assertEquals(2, s.rank(30));
        assertEquals(4, s.rank(50));
        assertEquals(0, s.rank(5));
        assertEquals(2, s.rank(25));
        assertEquals(5, s.rank(55));

        NavigableTreeSet<Integer> signed = setOf(Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE);
        assertEquals(0, signed.rank(Integer.MIN_VALUE));
        assertEquals(2, signed.rank(0));
        assertEquals(4, signed.rank(Integer.MAX_VALUE));
    }

    // ---- select: 0-based, out-of-range, negative --------------------------

    @Test
    public void mapSelectKeyZeroBasedAndOutOfRange()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30, 40, 50);
        assertEquals(Optional.of(10), m.selectKey(0));
        assertEquals(Optional.of(30), m.selectKey(2));
        assertEquals(Optional.of(50), m.selectKey(4));
        // i == size -> absence, no trap
        assertEquals(Optional.empty(), m.selectKey(5));
        assertEquals(Optional.empty(), m.selectKey(99));
    }

    @Test
    public void mapSelectKeyNegativeIsAbsenceNoTrap()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30);
        assertEquals(Optional.empty(), m.selectKey(-1));
        assertEquals(Optional.empty(), m.selectKey(Integer.MIN_VALUE));
    }

    @Test
    public void setSelectNegativeIsAbsenceNoTrap()
    {
        NavigableTreeSet<Integer> s = setOf(10, 20, 30);
        assertEquals(Optional.empty(), s.select(-1));
        assertEquals(Optional.empty(), s.select(Integer.MIN_VALUE));
        assertEquals(Optional.of(10), s.select(0));
        assertEquals(Optional.empty(), s.select(3));
    }

    @Test
    public void selectEntryValueCorrectness()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30, 40, 50);
        Optional<Map.Entry<Integer, Integer>> e = m.selectEntry(2);
        assertTrue(e.isPresent());
        assertEquals(30, e.get().getKey());
        assertEquals(300, e.get().getValue()); // value = key * 10 from mapOf
        assertEquals(Optional.empty(), m.selectEntry(5));
        assertEquals(Optional.empty(), m.selectEntry(-1));
    }

    // ---- edges: empty / single --------------------------------------------

    @Test
    public void emptyEdges()
    {
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.newMap();
        assertEquals(0, m.rank(5));
        assertEquals(Optional.empty(), m.selectKey(0));
        assertEquals(Optional.empty(), m.selectEntry(0));
        assertEquals(Optional.empty(), m.selectKey(-1));

        NavigableTreeSet<Integer> s = NavigableTreeSet.newSet();
        assertEquals(0, s.rank(5));
        assertEquals(Optional.empty(), s.select(0));
        assertEquals(Optional.empty(), s.select(-1));
    }

    @Test
    public void singleEdges()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(7);
        assertEquals(0, m.rank(6));
        assertEquals(0, m.rank(7));
        assertEquals(1, m.rank(8));
        assertEquals(Optional.of(7), m.selectKey(0));
        assertEquals(Optional.empty(), m.selectKey(1));

        NavigableTreeSet<Integer> s = setOf(7);
        assertEquals(0, s.rank(6));
        assertEquals(0, s.rank(7));
        assertEquals(1, s.rank(8));
        assertEquals(Optional.of(7), s.select(0));
        assertEquals(Optional.empty(), s.select(1));
    }

    // ---- uniqueness: re-insert does not add an order position -------------

    @Test
    public void reinsertIsIdempotentForOrder()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(10, 20, 30);
        m.put(20, 999); // update value, not a new order position
        assertEquals(3, m.size());
        assertEquals(1, m.rank(20));
        assertEquals(Optional.of(20), m.selectKey(1));
        assertEquals(999, m.selectEntry(1).get().getValue());

        NavigableTreeSet<Integer> s = setOf(10, 20, 30);
        assertFalse(s.add(20)); // idempotent
        assertEquals(3, s.size());
        assertEquals(1, s.rank(20));
    }

    // ---- round-trip identity ----------------------------------------------

    @Test
    public void roundTripOnFixedSet()
    {
        NavigableTreeMap<Integer, Integer> m = mapOf(-5, 0, 3, 42, 100);
        for (int k : new int[]{-5, 0, 3, 42, 100})
        {
            // select(rank(k)) == k for present k
            assertEquals(Optional.of(k), m.selectKey(m.rank(k)));
        }
        for (int i = 0; i < m.size(); i++)
        {
            // rank(select(i)) == i for 0 <= i < size
            assertEquals(i, m.rank(m.selectKey(i).get()));
        }
        // select(size) is absence
        assertEquals(Optional.empty(), m.selectKey(m.size()));
    }

    // ---- size consistency + round-trip under randomized insert/remove -----
    // The Java carve-out has no subtree-size field; the equivalent invariant is
    // that rank/select stay consistent with the live size and a reference
    // ordering through arbitrary insert/remove/transplant sequences.

    @Test
    public void randomizedInsertRemoveStaysConsistent()
    {
        Random rng = new Random(0xC0FFEE);
        NavigableTreeMap<Integer, Integer> m = NavigableTreeMap.newMap();
        TreeSet<Integer> ref = new TreeSet<>();

        for (int step = 0; step < 4000; step++)
        {
            int key = rng.nextInt(400) - 200; // span negatives too
            if (rng.nextBoolean())
            {
                m.put(key, key * 10);
                ref.add(key);
            }
            else
            {
                m.remove(key);
                ref.remove(key);
            }

            // Size stays in lockstep with the reference.
            assertEquals(ref.size(), m.size());

            // Spot-check rank/select against the reference every so often
            // (full scan every step would be O(n^2 * steps)).
            if (step % 37 == 0)
            {
                List<Integer> ordered = new ArrayList<>(ref);
                // rank of a present key, an absent-below, and an absent-above.
                if (!ordered.isEmpty())
                {
                    int idx = rng.nextInt(ordered.size());
                    int presentKey = ordered.get(idx);
                    assertEquals(idx, m.rank(presentKey));
                    assertEquals(Optional.of(presentKey), m.selectKey(idx));
                    // round-trip
                    assertEquals(presentKey, (int) m.selectKey(m.rank(presentKey)).get());
                }
                // rank of an absent key = lower-bound index.
                int probe = rng.nextInt(420) - 210;
                int expectedRank = (int) ordered.stream().filter(x -> x < probe).count();
                assertEquals(expectedRank, m.rank(probe));

                // out-of-range select.
                assertEquals(Optional.empty(), m.selectKey(m.size()));
                assertEquals(Optional.empty(), m.selectKey(-1));
            }
        }
    }
}
