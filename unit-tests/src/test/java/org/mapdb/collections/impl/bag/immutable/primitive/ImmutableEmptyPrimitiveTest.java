/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.bag.immutable.primitive;

import org.mapdb.collections.impl.factory.primitive.BooleanBags;
import org.mapdb.collections.impl.factory.primitive.ByteBags;
import org.mapdb.collections.impl.factory.primitive.CharBags;
import org.mapdb.collections.impl.factory.primitive.DoubleBags;
import org.mapdb.collections.impl.factory.primitive.FloatBags;
import org.mapdb.collections.impl.factory.primitive.IntBags;
import org.mapdb.collections.impl.factory.primitive.LongBags;
import org.mapdb.collections.impl.factory.primitive.ShortBags;
import org.mapdb.collections.impl.test.Verify;
import org.junit.jupiter.api.Test;

/**
 * JUnit test for empty() methods of primitive classes
 */
public class ImmutableEmptyPrimitiveTest
{
    @Test
    public void isEmptyImmutable()
    {
        Verify.assertEmpty(BooleanBags.immutable.empty());
        Verify.assertEmpty(BooleanBags.immutable.of());
        Verify.assertEmpty(BooleanBags.immutable.with());

        Verify.assertEmpty(ByteBags.immutable.empty());
        Verify.assertEmpty(ByteBags.immutable.of());
        Verify.assertEmpty(ByteBags.immutable.with());

        Verify.assertEmpty(CharBags.immutable.empty());
        Verify.assertEmpty(CharBags.immutable.of());
        Verify.assertEmpty(CharBags.immutable.with());

        Verify.assertEmpty(DoubleBags.immutable.empty());
        Verify.assertEmpty(DoubleBags.immutable.of());
        Verify.assertEmpty(DoubleBags.immutable.with());

        Verify.assertEmpty(FloatBags.immutable.empty());
        Verify.assertEmpty(FloatBags.immutable.of());
        Verify.assertEmpty(FloatBags.immutable.with());

        Verify.assertEmpty(IntBags.immutable.empty());
        Verify.assertEmpty(IntBags.immutable.of());
        Verify.assertEmpty(IntBags.immutable.with());

        Verify.assertEmpty(LongBags.immutable.empty());
        Verify.assertEmpty(LongBags.immutable.of());
        Verify.assertEmpty(LongBags.immutable.with());

        Verify.assertEmpty(ShortBags.immutable.empty());
        Verify.assertEmpty(ShortBags.immutable.of());
        Verify.assertEmpty(ShortBags.immutable.with());
    }
}
