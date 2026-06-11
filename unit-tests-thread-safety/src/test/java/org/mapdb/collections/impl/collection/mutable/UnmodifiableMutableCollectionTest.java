/*
 * Copyright (c) 2026 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.collection.mutable;

import org.mapdb.collections.api.RichIterable;
import org.mapdb.collections.impl.UnmodifiableIterableTestTrait;
import org.mapdb.collections.impl.list.mutable.FastList;

public class UnmodifiableMutableCollectionTest
        implements UnmodifiableIterableTestTrait
{
    @Override
    public RichIterable<String> getClassUnderTest()
    {
        return new UnmodifiableMutableCollection<>(FastList.newListWith("1", "2", "3"));
    }
}
