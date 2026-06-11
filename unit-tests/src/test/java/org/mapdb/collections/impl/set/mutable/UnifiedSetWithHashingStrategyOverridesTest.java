/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.set.mutable;

import org.mapdb.collections.api.block.HashingStrategy;
import org.mapdb.collections.api.set.MutableSet;
import org.mapdb.collections.impl.block.factory.HashingStrategies;
import org.mapdb.collections.impl.set.strategy.mutable.UnifiedSetWithHashingStrategy;

public class UnifiedSetWithHashingStrategyOverridesTest extends UnifiedSetWithHashingStrategyTest
{
    public static class UnifiedSetWithHashingStrategyOverrides<T> extends UnifiedSetWithHashingStrategy<T>
    {
        public UnifiedSetWithHashingStrategyOverrides(HashingStrategy<? super T> hashingStrategy, int initialCapacity)
        {
            super(hashingStrategy, initialCapacity);
        }

        @Override
        protected int index(T key)
        {
            int h = this.hashingStrategy.computeHashCode(key);
            return h & this.table.length - 1;
        }

        @Override
        public UnifiedSetWithHashingStrategyOverrides<T> newEmpty()
        {
            return new UnifiedSetWithHashingStrategyOverrides<>(this.hashingStrategy, 0);
        }

        @Override
        public UnifiedSetWithHashingStrategyOverrides<T> newEmpty(int size)
        {
            return new UnifiedSetWithHashingStrategyOverrides<>(this.hashingStrategy, size);
        }
    }

    @Override
    protected <T> MutableSet<T> newWith(T... littleElements)
    {
        HashingStrategy<T> nshs = HashingStrategies.nullSafeHashingStrategy(HashingStrategies.defaultStrategy());
        UnifiedSetWithHashingStrategyOverrides<T> set = new UnifiedSetWithHashingStrategyOverrides<>(nshs, littleElements.length);
        return set.with(littleElements);
    }
}
