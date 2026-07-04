// Copyright (c) 2026 Jan Kotek.
// Derived from Eclipse Collections (Copyright (c) Goldman Sachs and others).
// Licensed under the Eclipse Public License v1.0 and Eclipse Distribution License v1.0.
// See LICENSE-EPL-1.0.txt and LICENSE-EDL-1.0.txt.
// USE AT YOUR OWN RISK — THIS SOFTWARE IS PROVIDED WITHOUT WARRANTY OF ANY KIND.

package org.mapdb.collections.impl.digest;

import java.util.List;
import java.util.Map;

import org.mapdb.collections.impl.Hash;
import org.mapdb.collections.impl.RoaringU32;
import org.mapdb.collections.impl.sorted.ImmutableSortedMap;
import org.mapdb.collections.impl.sorted.ImmutableSortedSet;

/**
 * Archeology-2 "A2 — digest half" — a canonical <b>content digest</b> of a
 * collection, built entirely on the frozen, bit-exact {@link Hash} pipeline. Two
 * collections that are <i>logically equal</i> (same type, same members in
 * canonical order) produce the same 64-bit digest; two that differ produce
 * different digests with high probability. The digest is a language-neutral
 * identity for a collection: cheap cross-process equality, verifiable snapshots,
 * and the identity key that unlocks C2 content-addressing.
 *
 * <h2>Digest, not byte-format</h2>
 *
 * <p>This is deliberately <b>only the digest half</b> of A2. The other half — a
 * versioned, spec-pinned wire <i>byte-format</i> for reconstructing a collection
 * — is a forever cross-language decision (endianness, alignment, page layout) and
 * is not fixed here. The digest locks no such format: it folds each element
 * through the {@link Hash} pipeline's <i>already-frozen</i> per-element encoders
 * ({@link Hash#hash64Int32}, {@link Hash#encodeI32Word64}), so nothing new about
 * "how a collection is written to bytes" is committed. The only new contract is
 * the small combination algebra below (leaf/​node/​finalize rules + the domain
 * constants), which is why this ships as a {@code proposed}, Java-only spec.
 *
 * <h2>Merkle for ordered types</h2>
 *
 * <p>Following the {@code 00-README} sketch ("Merkle-style: subtree/page digests
 * for ordered types"), an ordered collection is digested as a binary
 * <b>Merkle tree</b> over its members in <b>canonical ascending order</b>:
 * <ul>
 *   <li>each member becomes a <b>leaf hash</b>;</li>
 *   <li>adjacent nodes are combined pairwise into a parent, bottom-up, until one
 *       <b>root</b> remains; a trailing odd node is <b>carried up unchanged</b>
 *       (Certificate-Transparency style — never self-duplicated, which would
 *       admit collisions);</li>
 *   <li>the root is <b>finalized</b> by binding the collection's <b>type tag</b>
 *       and <b>element count</b>, so an empty set, an empty map and a one-element
 *       collection are all distinct, and a set can never collide with a map of
 *       the same members.</li>
 * </ul>
 * Because it is a tree (not a flat fold), a single member's <b>inclusion</b> can
 * be proved and verified in {@code O(log n)} against the root alone — see
 * {@link #proveMember} / {@link #verifyMember}. That is the property that turns a
 * digest into a <i>verifiable</i> snapshot.
 *
 * <h2>The combiner must not be symmetric — a Hash-pipeline trap</h2>
 *
 * <p>{@link Hash#hash64}{@code (word, seed)} is exactly {@code fmix64(word ^
 * seed)}: it XORs its two arguments <i>before</i> the (bijective) finalizer.
 * A combiner written naively as {@code hash64(left, right)} would therefore be
 * <b>symmetric</b> ({@code node(a,b) == node(b,a)}, so sibling order is invisible)
 * and would <b>collapse equal children</b> ({@code node(a,a)} is a constant
 * independent of {@code a}). Both are classic Merkle collision sources. Every
 * combiner here instead passes one operand through an <b>extra</b> {@code fmix64}
 * round before the XOR, making the result a <b>bijection in each argument
 * separately</b> — order-sensitive and collapse-free — while still being built
 * only from the frozen primitive.
 *
 * <h2>Not a cryptographic commitment</h2>
 *
 * <p>The digest is 64-bit, matching the {@code i32} validation universe's scale.
 * It is engineered against <i>accidental</i> divergence (corruption, wrong data,
 * order/type confusion) with birthday-bound collision resistance near
 * {@code 2^32}. It is <b>not</b> a cryptographic commitment against an adversary
 * searching for collisions. A 128/256-bit variant is a trivial follow-up (run the
 * tree under two/four independent seed lanes and concatenate); it is intentionally
 * out of this slice.
 *
 * <h2>Scope</h2>
 *
 * <p>Typed entry points cover the three {@code i32} collections that have a
 * canonical order today: {@link ImmutableSortedSet} of {@code Integer},
 * {@link ImmutableSortedMap} of {@code Integer}→{@code Integer}, and
 * {@link RoaringU32} (unsigned-{@code u32} ascending members). The
 * "order-insensitive fold for hash types" the README also names is a no-op today
 * (Bloom/HLL/CMS are separate, deferred features with no member enumeration to
 * fold); it is a documented follow-up, not implemented here.
 */
public final class CollectionDigest
{
    // ---- Domain constants -------------------------------------------------
    //
    // Arbitrary but fixed 64-bit domain separators. They are part of the
    // (proposed) digest contract: any other port must use these exact values or
    // its digests will not match. They are not derived from the input and carry
    // no algebraic relationship to the Fibonacci/​golden collection constants.

    /** Seed for a set/Roaring member leaf hash. */
    private static final long LEAF_SET_SEED = 0xA2D19E5710EAF001L;

    /** First seed for a map-entry leaf (mixes the key). */
    private static final long LEAF_MAP_KEY_SEED = 0xA2D19E577A1E0002L;

    /** Second seed for a map-entry leaf (mixes the value). */
    private static final long LEAF_MAP_VAL_SEED = 0xA2D19E577A1E0003L;

    /** Seed applied to the pre-mixed left child in {@link #node}. */
    private static final long NODE_LEFT_SEED = 0xA2D19E5710DE0004L;

    /** Seed XOR'd with the right child in {@link #node}. */
    private static final long NODE_RIGHT_SEED = 0xA2D19E5710DE0005L;

    /** Merkle root of an empty collection (before type/​size finalization). */
    private static final long EMPTY_ROOT = 0xA2D19E57E3B00006L;

    /** First finalize seed (binds the type tag to the root). */
    private static final long FINAL_TAG_SEED = 0xA2D19E57F1A70007L;

    /** Second finalize seed (binds the element count). */
    private static final long FINAL_SIZE_SEED = 0xA2D19E57F1A70008L;

    /** Type tag: {@link ImmutableSortedSet}. */
    private static final long TAG_SORTED_SET = 0x01L;

    /** Type tag: {@link ImmutableSortedMap}. */
    private static final long TAG_SORTED_MAP = 0x02L;

    /** Type tag: {@link RoaringU32}. */
    private static final long TAG_ROARING = 0x03L;

    /**
     * Lane salt for the primary (only, for the 64-bit API) digest lane — zero, so
     * every 64-bit result is byte-identical to a lane-unaware computation.
     */
    private static final long LANE_0 = 0L;

    /**
     * Salt for the second digest lane used by the 128-bit variants: XOR'd into
     * every seed so lane 1 is a <b>decorrelated re-seeded</b> re-hash of the same
     * Merkle structure — the same two-seed technique {@link Hash#positions} already
     * uses ({@code hash32(x, 0)} vs {@code hash32(x, SALT2)}). The
     * {@code (lane0, lane1)} pair raises the <i>accidental</i>-collision birthday
     * bound toward ~{@code 2^64}; this is a heuristic under the non-cryptographic
     * model, <b>not</b> a proven independent-lane / cryptographic 128-bit guarantee
     * (a single fixed 64-bit finalizer cannot provide that).
     */
    private static final long LANE_1 = 0xC0FFEE1237A2D105L;

    /**
     * Domain XOR'd into the caller's tag inside {@link #combine}, so a composite
     * digest of child digests can never share the finalize domain with a primitive
     * collection digest even if the caller passes a colliding tag value.
     */
    private static final long COMPOSITE_DOMAIN = 0xC0117A11E0000009L;

    private CollectionDigest()
    {
    }

    // ---- Leaf and node algebra -------------------------------------------

    /**
     * Leaf hash of a set/Roaring {@code i32} member: {@code fmix64(zext(v) ^
     * LEAF_SET_SEED)} via the frozen {@link Hash#hash64Int32}. Distinct members
     * yield distinct leaves (zero-extend is injective on {@code i32};
     * {@code fmix64} is bijective).
     */
    private static long leafOfMember(int value)
    {
        return leafOfMember(value, LANE_0);
    }

    /** Lane-salted {@link #leafOfMember}: {@code fmix64(zext(v) ^ LEAF_SET_SEED ^ lane)}. */
    private static long leafOfMember(int value, long lane)
    {
        return Hash.hash64Int32(value, LEAF_SET_SEED ^ lane);
    }

    /**
     * Leaf hash of a map entry {@code (key, value)}: two frozen rounds,
     * {@code fmix64(fmix64(zext(key) ^ KEY_SEED) ^ zext(value) ^ VAL_SEED)}. The
     * extra round on the key makes the leaf asymmetric in {@code (key, value)} and
     * domain-separates it from a bare set member.
     */
    private static long leafOfEntry(int key, int value)
    {
        return leafOfEntry(key, value, LANE_0);
    }

    /** Lane-salted {@link #leafOfEntry}. */
    private static long leafOfEntry(int key, int value, long lane)
    {
        long k = Hash.hash64(Hash.encodeI32Word64(key), LEAF_MAP_KEY_SEED ^ lane);
        return Hash.hash64(k, Hash.encodeI32Word64(value) ^ LEAF_MAP_VAL_SEED ^ lane);
    }

    /**
     * Combine two child digests into a parent. Built from the frozen
     * {@link Hash#hash64} but engineered to avoid its symmetry: {@code left} is
     * pre-mixed through an <b>extra</b> {@code fmix64} round before the XOR, so
     * {@code node(l, r) = fmix64(fmix64(l ^ NODE_LEFT_SEED) ^ r ^ NODE_RIGHT_SEED)}
     * is a bijection in each argument separately — order-sensitive and never
     * collapsing on equal children.
     */
    private static long node(long left, long right)
    {
        return node(left, right, LANE_0);
    }

    /** Lane-salted {@link #node}. */
    private static long node(long left, long right, long lane)
    {
        long l = Hash.hash64(left, NODE_LEFT_SEED ^ lane);
        return Hash.hash64(l, right ^ NODE_RIGHT_SEED ^ lane);
    }

    /**
     * Finalize a Merkle root into the collection digest by binding the type tag
     * then the element count: {@code fmix64(fmix64(root ^ tag ^ FINAL_TAG_SEED) ^
     * count ^ FINAL_SIZE_SEED)}. Binding the count distinguishes a one-element
     * collection from its lone leaf and pins the empty case per type.
     */
    private static long finalizeRoot(long root, long typeTag, long count)
    {
        return finalizeRoot(root, typeTag, count, LANE_0);
    }

    /** Lane-salted {@link #finalizeRoot}. */
    private static long finalizeRoot(long root, long typeTag, long count, long lane)
    {
        long t = Hash.hash64(root, typeTag ^ FINAL_TAG_SEED ^ lane);
        return Hash.hash64(t, count ^ FINAL_SIZE_SEED ^ lane);
    }

    /**
     * Fold one Merkle level into the next: pair adjacent nodes into parents; a
     * trailing odd node is carried up unchanged.
     */
    private static long[] nextLevel(long[] level)
    {
        return nextLevel(level, LANE_0);
    }

    /** Lane-salted {@link #nextLevel}. */
    private static long[] nextLevel(long[] level, long lane)
    {
        int pairs = level.length >>> 1;
        boolean odd = (level.length & 1) == 1;
        long[] next = new long[pairs + (odd ? 1 : 0)];
        for (int i = 0; i < pairs; i++)
        {
            next[i] = node(level[2 * i], level[2 * i + 1], lane);
        }
        if (odd)
        {
            next[pairs] = level[level.length - 1];
        }
        return next;
    }

    /** Merkle root over an ordered array of leaf hashes (empty ⇒ {@code EMPTY_ROOT}). */
    private static long merkleRoot(long[] leaves)
    {
        return merkleRoot(leaves, LANE_0);
    }

    /** Lane-salted {@link #merkleRoot} (empty ⇒ {@code EMPTY_ROOT ^ lane}). */
    private static long merkleRoot(long[] leaves, long lane)
    {
        if (leaves.length == 0)
        {
            return EMPTY_ROOT ^ lane;
        }
        long[] level = leaves;
        while (level.length > 1)
        {
            level = nextLevel(level, lane);
        }
        return level[0];
    }

    // ---- Typed digest entry points (64-bit) ------------------------------

    /** Content digest of a sorted set of {@code i32} members (ascending order). */
    public static long ofSortedSet(ImmutableSortedSet<Integer> set)
    {
        return ofMembers(memberArray(set.elements()), TAG_SORTED_SET, LANE_0);
    }

    /** Content digest of a sorted {@code i32}→{@code i32} map (ascending by key). */
    public static long ofSortedMap(ImmutableSortedMap<Integer, Integer> map)
    {
        return ofEntries(map.entries(), LANE_0);
    }

    /**
     * Content digest of a {@link RoaringU32} set, over its members in
     * <b>unsigned-{@code u32} ascending</b> order (matching
     * {@link RoaringU32#toSortedArray}). A Roaring set and an
     * {@link ImmutableSortedSet} with identical members digest <b>differently</b>
     * by design: they are different collection types (distinct type tag).
     *
     * <p><b>Scope (this slice):</b> the digest materialises members via
     * {@link RoaringU32#toSortedArray}, so it is defined only for
     * {@code cardinality() <= Integer.MAX_VALUE} — the entire {@code i32}
     * validation universe. A Roaring set with more than {@code 2^31 - 1} members
     * (legal for {@code RoaringU32}, but ~8&nbsp;GB of members and never reached by
     * the suite) is rejected with a clear message rather than silently leaking the
     * array limit. Digesting such a set needs a streaming, chunk-ordered Merkle
     * over the container structure — a documented follow-up, deliberately out of
     * this slice so the three typed digests share one array-based algorithm.
     *
     * @throws IllegalArgumentException if {@code bits.cardinality() > Integer.MAX_VALUE}
     */
    public static long ofRoaring(RoaringU32 bits)
    {
        return ofMembers(materializableMembers(bits), TAG_ROARING, LANE_0);
    }

    // ---- 128-bit variants (two independent lanes) ------------------------

    /**
     * 128-bit content digest of a sorted set as {@code {lane0, lane1}}. Lane 1 is a
     * decorrelated re-seeded re-hash of the same tree under {@link #LANE_1}, so an
     * accidental collision must hit <i>both</i> lanes — raising the birthday bound
     * toward ~{@code 2^64} under the non-cryptographic model (heuristic, not a
     * proven independent-lane guarantee — see {@link #LANE_1}). Lane 0 equals
     * {@link #ofSortedSet}. Use when a memoisation or verification cache must be
     * robust against accidental collisions at scale.
     */
    public static long[] ofSortedSet128(ImmutableSortedSet<Integer> set)
    {
        int[] m = memberArray(set.elements());
        return new long[] {ofMembers(m, TAG_SORTED_SET, LANE_0), ofMembers(m, TAG_SORTED_SET, LANE_1)};
    }

    /** 128-bit content digest of a sorted map as {@code {lane0, lane1}} (lane 0 = {@link #ofSortedMap}). */
    public static long[] ofSortedMap128(ImmutableSortedMap<Integer, Integer> map)
    {
        List<Map.Entry<Integer, Integer>> entries = map.entries();
        return new long[] {ofEntries(entries, LANE_0), ofEntries(entries, LANE_1)};
    }

    /** 128-bit content digest of a Roaring set as {@code {lane0, lane1}} (lane 0 = {@link #ofRoaring}). */
    public static long[] ofRoaring128(RoaringU32 bits)
    {
        int[] m = materializableMembers(bits);
        return new long[] {ofMembers(m, TAG_ROARING, LANE_0), ofMembers(m, TAG_ROARING, LANE_1)};
    }

    // ---- Composite digest (digest of child digests) ----------------------

    /**
     * Compose an <b>ordered</b> list of child digests into one — a digest of
     * digests, for content-addressing structures built from several collections
     * (e.g. a columnar table = key column + value columns, or a multi-input
     * pipeline stage keyed by all its inputs' digests). The children are the
     * leaves of a Merkle tree finalized with a tag <b>derived by hashing</b> the
     * caller's {@code typeTag} through a fixed composite domain
     * ({@code hash64(typeTag, COMPOSITE_DOMAIN)}) plus the child count. Hashing
     * (rather than a reversible XOR) means no caller {@code typeTag} can
     * accidentally reproduce a primitive collection's finalize tag, so a composite
     * digest is domain-separated from every primitive digest under the
     * accidental-collision model (it is not an adversarial commitment — nothing
     * here is). Order-sensitive: reordering the children changes the result.
     *
     * @param typeTag  a caller-chosen domain for this kind of composite
     * @param children the child digests, in a canonical order the caller defines
     */
    public static long combine(long typeTag, long[] children)
    {
        long domainTag = Hash.hash64(typeTag, COMPOSITE_DOMAIN);
        return finalizeRoot(merkleRoot(children, LANE_0), domainTag, children.length, LANE_0);
    }

    // ---- shared leaf-building helpers ------------------------------------

    private static int[] memberArray(List<Integer> elems)
    {
        int[] a = new int[elems.size()];
        for (int i = 0; i < a.length; i++)
        {
            a[i] = elems.get(i);
        }
        return a;
    }

    /** Digest of a member array under a type tag and lane (set / Roaring). */
    private static long ofMembers(int[] members, long typeTag, long lane)
    {
        long[] leaves = new long[members.length];
        for (int i = 0; i < members.length; i++)
        {
            leaves[i] = leafOfMember(members[i], lane);
        }
        return finalizeRoot(merkleRoot(leaves, lane), typeTag, members.length, lane);
    }

    /** Digest of map entries under a lane. */
    private static long ofEntries(List<Map.Entry<Integer, Integer>> entries, long lane)
    {
        long[] leaves = new long[entries.size()];
        for (int i = 0; i < leaves.length; i++)
        {
            Map.Entry<Integer, Integer> e = entries.get(i);
            leaves[i] = leafOfEntry(e.getKey(), e.getValue(), lane);
        }
        return finalizeRoot(merkleRoot(leaves, lane), TAG_SORTED_MAP, entries.size(), lane);
    }

    /** Materialise a Roaring set's members, rejecting the &gt; {@code 2^31} case. */
    private static int[] materializableMembers(RoaringU32 bits)
    {
        if (bits.cardinality() > Integer.MAX_VALUE)
        {
            throw new IllegalArgumentException(
                    "CollectionDigest.ofRoaring is defined for cardinality <= Integer.MAX_VALUE"
                            + " (this slice materialises members); got " + bits.cardinality());
        }
        return bits.toSortedArray();
    }

    // ---- Inclusion proofs -------------------------------------------------

    /**
     * A Merkle <b>inclusion proof</b>: the {@code O(log n)} sibling path from a
     * leaf to the root, plus the element count needed to reproduce the finalized
     * digest. It does <b>not</b> carry the proved member itself — the verifier
     * supplies (and re-hashes) that, which is what makes the proof a genuine
     * check rather than a lookup.
     */
    public static final class MerkleProof
    {
        /** Sibling digest at each level, bottom (leaf) to top; unused where absent. */
        private final long[] sibling;

        /** Whether a sibling exists at this level (false ⇒ odd carry, parent = node itself). */
        private final boolean[] present;

        /** Whether the sibling is the <b>left</b> child (so our node is the right one). */
        private final boolean[] siblingIsLeft;

        /** The collection's element count, bound into the finalized digest. */
        private final long count;

        private MerkleProof(long[] sibling, boolean[] present, boolean[] siblingIsLeft, long count)
        {
            this.sibling = sibling;
            this.present = present;
            this.siblingIsLeft = siblingIsLeft;
            this.count = count;
        }

        /** Number of levels climbed (0 for a singleton collection). */
        public int height()
        {
            return this.sibling.length;
        }

        /** The element count this proof commits to. */
        public long count()
        {
            return this.count;
        }
    }

    /**
     * Build the sibling path for the leaf at {@code index} in a tree of the given
     * leaves, committing to {@code count} (which equals {@code leaves.length} for
     * a set/map, but may exceed {@code 2^31} for Roaring — kept separate so the
     * proof binds the same count the digest did).
     */
    private static MerkleProof proofFor(long[] leaves, int index, long count)
    {
        // Height = number of levels above the leaves = ceil(log2(n)) for n>1.
        int levels = 0;
        for (int len = leaves.length; len > 1; len = (len >>> 1) + (len & 1))
        {
            levels++;
        }
        long[] sibling = new long[levels];
        boolean[] present = new boolean[levels];
        boolean[] siblingIsLeft = new boolean[levels];

        long[] level = leaves;
        int idx = index;
        for (int lvl = 0; lvl < levels; lvl++)
        {
            int pairs = level.length >>> 1;
            if (idx < 2 * pairs)
            {
                boolean isLeftChild = (idx & 1) == 0;
                int sibIdx = isLeftChild ? idx + 1 : idx - 1;
                present[lvl] = true;
                sibling[lvl] = level[sibIdx];
                // If our node is the left child, its sibling sits on the right.
                siblingIsLeft[lvl] = !isLeftChild;
            }
            else
            {
                // Our node is the trailing odd one: no sibling, carried up.
                present[lvl] = false;
            }
            level = nextLevel(level);
            idx >>>= 1;
        }
        return new MerkleProof(sibling, present, siblingIsLeft, count);
    }

    /** Replay a proof from a leaf hash back up to the (pre-finalize) root. */
    private static long replay(long leaf, MerkleProof proof)
    {
        long acc = leaf;
        for (int lvl = 0; lvl < proof.sibling.length; lvl++)
        {
            if (!proof.present[lvl])
            {
                continue; // odd carry: parent is the node itself
            }
            acc = proof.siblingIsLeft[lvl]
                    ? node(proof.sibling[lvl], acc)
                    : node(acc, proof.sibling[lvl]);
        }
        return acc;
    }

    /**
     * Produce an inclusion proof that {@code element} is a member of {@code set}.
     *
     * @throws IllegalArgumentException if the element is not present
     */
    public static MerkleProof proveMember(ImmutableSortedSet<Integer> set, int element)
    {
        if (!set.contains(element))
        {
            throw new IllegalArgumentException("element not in set: " + element);
        }
        List<Integer> elems = set.elements();
        long[] leaves = new long[elems.size()];
        for (int i = 0; i < leaves.length; i++)
        {
            leaves[i] = leafOfMember(elems.get(i));
        }
        return proofFor(leaves, set.rank(element), leaves.length);
    }

    /**
     * Verify an inclusion proof: recompute {@code element}'s leaf, replay the
     * sibling path to a root, finalize with the sorted-set type tag and the
     * proof's committed count, and compare against {@code setDigest}. Returns
     * {@code true} iff the proof genuinely attests membership under that digest —
     * a tampered element, sibling, direction or count fails.
     */
    public static boolean verifyMember(long setDigest, int element, MerkleProof proof)
    {
        long root = replay(leafOfMember(element), proof);
        return finalizeRoot(root, TAG_SORTED_SET, proof.count) == setDigest;
    }

    /**
     * Produce an inclusion proof for the entry at {@code key} in {@code map}
     * (proving both the key's presence and its exact value).
     *
     * @throws IllegalArgumentException if the key is not present
     */
    public static MerkleProof proveEntry(ImmutableSortedMap<Integer, Integer> map, int key)
    {
        if (!map.containsKey(key))
        {
            throw new IllegalArgumentException("key not in map: " + key);
        }
        List<Map.Entry<Integer, Integer>> entries = map.entries();
        long[] leaves = new long[entries.size()];
        for (int i = 0; i < leaves.length; i++)
        {
            Map.Entry<Integer, Integer> e = entries.get(i);
            leaves[i] = leafOfEntry(e.getKey(), e.getValue());
        }
        return proofFor(leaves, map.rank(key), leaves.length);
    }

    /**
     * Verify an entry inclusion proof against {@code mapDigest}: recompute the
     * {@code (key, value)} leaf, replay, finalize with the map type tag and the
     * committed count, and compare. A wrong value (as well as a tampered path or
     * count) fails.
     */
    public static boolean verifyEntry(long mapDigest, int key, int value, MerkleProof proof)
    {
        long root = replay(leafOfEntry(key, value), proof);
        return finalizeRoot(root, TAG_SORTED_MAP, proof.count) == mapDigest;
    }
}
