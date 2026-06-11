/*
 * Copyright (c) 2024 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.list.fixed;

import org.mapdb.collections.api.factory.Lists;
import org.mapdb.collections.api.list.MutableList;

public class SingletonListSubListTest extends UnmodifiableMemoryEfficientListTestCase<String>
{
    @Override
    protected MutableList<String> getCollection()
    {
        return Lists.fixedSize.of("1").subList(0, 1);
    }
}
