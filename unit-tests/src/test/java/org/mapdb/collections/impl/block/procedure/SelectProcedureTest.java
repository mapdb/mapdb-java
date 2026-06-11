/*
 * Copyright (c) 2021 The Bank of New York Mellon.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.impl.block.procedure;

import org.mapdb.collections.api.factory.Lists;
import org.mapdb.collections.impl.block.factory.Predicates;
import org.mapdb.collections.impl.test.Verify;
import org.junit.jupiter.api.Test;

public class SelectProcedureTest
{
    @Test
    public void getCollection()
    {
        SelectProcedure<Integer> selectProcedure = new SelectProcedure<>(Predicates.alwaysTrue(), Lists.mutable.empty());
        Verify.assertEmpty(selectProcedure.getCollection());

        selectProcedure.value(1);
        Verify.assertSize(1, selectProcedure.getCollection());
        Verify.assertContainsAll(selectProcedure.getCollection(), 1);

        selectProcedure.value(2);
        Verify.assertSize(2, selectProcedure.getCollection());
        Verify.assertContainsAll(selectProcedure.getCollection(), 1, 2);
    }
}
