/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.block.factory;

import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.api.block.function.Function;
import org.mapdb.collections.api.multimap.Multimap;

public final class MultimapFunctions
{
    private MultimapFunctions()
    {
        throw new AssertionError("Suppress default constructor for noninstantiability");
    }

    /**
     * @return Function delegating to {@link Multimap#get(Object)}
     */
    public static <K, V> Function<K, RichIterable<V>> get(Multimap<K, V> multimap)
    {
        return new MultimapGetFunction<>(multimap);
    }

    private static final class MultimapGetFunction<K, V> implements Function<K, RichIterable<V>>
    {
        private static final long serialVersionUID = 1L;

        private final Multimap<K, V> multimap;

        private MultimapGetFunction(Multimap<K, V> multimap)
        {
            this.multimap = multimap;
        }

        @Override
        public RichIterable<V> valueOf(K subject)
        {
            return this.multimap.get(subject);
        }
    }
}
