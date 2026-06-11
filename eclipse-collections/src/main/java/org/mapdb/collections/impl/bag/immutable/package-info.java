/*
 * Copyright (c) 2021 Goldman Sachs.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

/**
 * This package contains implementations of the {@link org.mapdb.collections.api.bag.ImmutableBag} interface.
 * <p>
 *     An {@link org.mapdb.collections.api.bag.ImmutableBag} is an immutable collection which contains elements that are unordered, and may contain duplicate entries.
 * <p>
 *     This package contains 4 immutable bag implementations:
 * <ul>
 *     <li>
 *          {@link org.mapdb.collections.impl.bag.immutable.ImmutableArrayBag} - an {@link org.mapdb.collections.api.bag.ImmutableBag} which uses an array as its underlying data store.
 *     </li>
 *     <li>
 *          {@link org.mapdb.collections.impl.bag.immutable.ImmutableEmptyBag} - an empty {@link org.mapdb.collections.api.bag.ImmutableBag}.
 *     </li>
 *     <li>
 *          {@link org.mapdb.collections.impl.bag.immutable.ImmutableHashBag} - an {@link org.mapdb.collections.api.bag.ImmutableBag} which uses a hashtable as its underlying data store.
 *     </li>
 *     <li>
 *          {@link org.mapdb.collections.impl.bag.immutable.ImmutableSingletonBag} - an {@link org.mapdb.collections.api.bag.ImmutableBag} which contains only one element.
 *     </li>
 * </ul>
 * <p>
 *     This package contains one factory implementation:
 * <ul>
 *     <li>
 *         {@link org.mapdb.collections.impl.bag.immutable.ImmutableBagFactoryImpl} - a factory which creates instances of type {@link org.mapdb.collections.api.bag.ImmutableBag}.
 *     </li>
 * </ul>
 */
package org.mapdb.collections.impl.bag.immutable;
