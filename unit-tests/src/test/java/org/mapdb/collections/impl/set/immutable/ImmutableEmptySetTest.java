/*
 * Copyright (c) 2024 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.set.immutable;

import org.mapdb.collections.api.bag.MutableBag;
import org.mapdb.collections.api.factory.Bags;
import org.mapdb.collections.api.factory.Sets;
import org.mapdb.collections.api.set.ImmutableSet;
import org.mapdb.collections.api.set.MutableSet;
import org.mapdb.collections.impl.list.Interval;
import org.mapdb.collections.impl.list.mutable.FastList;
import org.mapdb.collections.impl.list.primitive.IntInterval;
import org.mapdb.collections.impl.set.mutable.UnifiedSet;
import org.mapdb.collections.impl.test.Verify;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class ImmutableEmptySetTest extends AbstractImmutableEmptySetTestCase
{
    @Override
    protected ImmutableSet<Integer> classUnderTest()
    {
        return Sets.immutable.of();
    }

    @Override
    @Test
    public void newWithout()
    {
        assertSame(Sets.immutable.of(), Sets.immutable.of().newWithout(1));
        assertSame(Sets.immutable.of(), Sets.immutable.of().newWithoutAll(Interval.oneTo(3)));
    }

    @Override
    @Test
    public void equalsAndHashCode()
    {
        ImmutableSet<Integer> immutable = this.classUnderTest();
        MutableSet<Integer> mutable = UnifiedSet.newSet(immutable);
        Verify.assertEqualsAndHashCode(mutable, immutable);
        Verify.assertPostSerializedIdentity(immutable);
        assertNotEquals(FastList.newList(mutable), immutable);
    }

    @Override
    @Test
    public void countByEach()
    {
        assertEquals(Bags.immutable.empty(), this.classUnderTest().countByEach(each -> IntInterval.oneTo(5).collect(i -> each + i)));
    }

    @Test
    public void countByEach_target()
    {
        MutableBag<Integer> target = Bags.mutable.empty();
        assertEquals(target, this.classUnderTest().countByEach(each -> IntInterval.oneTo(5).collect(i -> each + i), target));
    }
}
