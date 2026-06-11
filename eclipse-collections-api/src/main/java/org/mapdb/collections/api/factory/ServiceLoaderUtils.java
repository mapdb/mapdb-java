/*
 * Copyright (c) 2021 Goldman Sachs and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Eclipse Distribution License v. 1.0 which accompany this distribution.
 * The Eclipse Public License is available at http://www.eclipse.org/legal/epl-v10.html
 * and the Eclipse Distribution License is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 */

package org.mapdb.collections.api.factory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

public final class ServiceLoaderUtils
{
    private static final Map<String, String> FACTORY_IMPL = new HashMap<>();

    static
    {
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.ImmutableBagFactory", "org.mapdb.collections.impl.bag.immutable.ImmutableBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.MultiReaderBagFactory", "org.mapdb.collections.impl.bag.mutable.MultiReaderMutableBagFactory");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.MutableBagFactory", "org.mapdb.collections.impl.bag.mutable.MutableBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.ImmutableBooleanBagFactory", "org.mapdb.collections.impl.bag.immutable.primitive.ImmutableBooleanBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.ImmutableByteBagFactory", "org.mapdb.collections.impl.bag.immutable.primitive.ImmutableByteBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.ImmutableCharBagFactory", "org.mapdb.collections.impl.bag.immutable.primitive.ImmutableCharBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.ImmutableDoubleBagFactory", "org.mapdb.collections.impl.bag.immutable.primitive.ImmutableDoubleBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.ImmutableFloatBagFactory", "org.mapdb.collections.impl.bag.immutable.primitive.ImmutableFloatBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.ImmutableIntBagFactory", "org.mapdb.collections.impl.bag.immutable.primitive.ImmutableIntBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.ImmutableLongBagFactory", "org.mapdb.collections.impl.bag.immutable.primitive.ImmutableLongBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.ImmutableShortBagFactory", "org.mapdb.collections.impl.bag.immutable.primitive.ImmutableShortBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.MutableBooleanBagFactory", "org.mapdb.collections.impl.bag.mutable.primitive.MutableBooleanBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.MutableByteBagFactory", "org.mapdb.collections.impl.bag.mutable.primitive.MutableByteBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.MutableCharBagFactory", "org.mapdb.collections.impl.bag.mutable.primitive.MutableCharBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.MutableDoubleBagFactory", "org.mapdb.collections.impl.bag.mutable.primitive.MutableDoubleBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.MutableFloatBagFactory", "org.mapdb.collections.impl.bag.mutable.primitive.MutableFloatBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.MutableIntBagFactory", "org.mapdb.collections.impl.bag.mutable.primitive.MutableIntBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.MutableLongBagFactory", "org.mapdb.collections.impl.bag.mutable.primitive.MutableLongBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.primitive.MutableShortBagFactory", "org.mapdb.collections.impl.bag.mutable.primitive.MutableShortBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.sorted.ImmutableSortedBagFactory", "org.mapdb.collections.impl.bag.sorted.immutable.ImmutableSortedBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bag.sorted.MutableSortedBagFactory", "org.mapdb.collections.impl.bag.sorted.mutable.MutableSortedBagFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bimap.ImmutableBiMapFactory", "org.mapdb.collections.impl.bimap.immutable.ImmutableBiMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.bimap.MutableBiMapFactory", "org.mapdb.collections.impl.bimap.mutable.MutableBiMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.FixedSizeListFactory", "org.mapdb.collections.impl.list.fixed.FixedSizeListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.ImmutableListFactory", "org.mapdb.collections.impl.list.immutable.ImmutableListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.MultiReaderListFactory", "org.mapdb.collections.impl.list.mutable.MultiReaderMutableListFactory");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.MutableListFactory", "org.mapdb.collections.impl.list.mutable.MutableListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.ImmutableBooleanListFactory", "org.mapdb.collections.impl.list.immutable.primitive.ImmutableBooleanListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.ImmutableByteListFactory", "org.mapdb.collections.impl.list.immutable.primitive.ImmutableByteListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.ImmutableCharListFactory", "org.mapdb.collections.impl.list.immutable.primitive.ImmutableCharListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.ImmutableDoubleListFactory", "org.mapdb.collections.impl.list.immutable.primitive.ImmutableDoubleListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.ImmutableFloatListFactory", "org.mapdb.collections.impl.list.immutable.primitive.ImmutableFloatListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.ImmutableIntListFactory", "org.mapdb.collections.impl.list.immutable.primitive.ImmutableIntListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.ImmutableLongListFactory", "org.mapdb.collections.impl.list.immutable.primitive.ImmutableLongListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.ImmutableShortListFactory", "org.mapdb.collections.impl.list.immutable.primitive.ImmutableShortListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.MutableBooleanListFactory", "org.mapdb.collections.impl.list.mutable.primitive.MutableBooleanListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.MutableByteListFactory", "org.mapdb.collections.impl.list.mutable.primitive.MutableByteListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.MutableCharListFactory", "org.mapdb.collections.impl.list.mutable.primitive.MutableCharListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.MutableDoubleListFactory", "org.mapdb.collections.impl.list.mutable.primitive.MutableDoubleListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.MutableFloatListFactory", "org.mapdb.collections.impl.list.mutable.primitive.MutableFloatListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.MutableIntListFactory", "org.mapdb.collections.impl.list.mutable.primitive.MutableIntListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.MutableLongListFactory", "org.mapdb.collections.impl.list.mutable.primitive.MutableLongListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.list.primitive.MutableShortListFactory", "org.mapdb.collections.impl.list.mutable.primitive.MutableShortListFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.FixedSizeMapFactory", "org.mapdb.collections.impl.map.fixed.FixedSizeMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.ImmutableMapFactory", "org.mapdb.collections.impl.map.immutable.ImmutableMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.MutableMapFactory", "org.mapdb.collections.impl.map.mutable.MutableMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableBooleanBooleanMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableBooleanBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableBooleanByteMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableBooleanByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableBooleanCharMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableBooleanCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableBooleanDoubleMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableBooleanDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableBooleanFloatMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableBooleanFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableBooleanIntMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableBooleanIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableBooleanLongMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableBooleanLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableBooleanShortMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableBooleanShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableByteBooleanMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableByteBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableByteByteMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableByteByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableByteCharMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableByteCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableByteDoubleMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableByteDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableByteFloatMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableByteFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableByteIntMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableByteIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableByteLongMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableByteLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableByteObjectMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableByteObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableByteShortMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableByteShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableCharBooleanMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableCharBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableCharByteMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableCharByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableCharCharMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableCharCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableCharDoubleMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableCharDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableCharFloatMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableCharFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableCharIntMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableCharIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableCharLongMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableCharLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableCharObjectMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableCharObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableCharShortMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableCharShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableDoubleBooleanMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableDoubleBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableDoubleByteMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableDoubleByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableDoubleCharMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableDoubleCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableDoubleDoubleMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableDoubleDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableDoubleFloatMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableDoubleFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableDoubleIntMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableDoubleIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableDoubleLongMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableDoubleLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableDoubleObjectMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableDoubleObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableDoubleShortMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableDoubleShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableFloatBooleanMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableFloatBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableFloatByteMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableFloatByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableFloatCharMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableFloatCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableFloatDoubleMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableFloatDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableFloatFloatMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableFloatFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableFloatIntMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableFloatIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableFloatLongMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableFloatLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableFloatObjectMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableFloatObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableFloatShortMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableFloatShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableIntBooleanMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableIntBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableIntByteMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableIntByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableIntCharMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableIntCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableIntDoubleMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableIntDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableIntFloatMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableIntFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableIntIntMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableIntIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableIntLongMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableIntLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableIntObjectMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableIntObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableIntShortMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableIntShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableLongBooleanMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableLongBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableLongByteMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableLongByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableLongCharMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableLongCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableLongDoubleMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableLongDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableLongFloatMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableLongFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableLongIntMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableLongIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableLongLongMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableLongLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableLongObjectMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableLongObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableLongShortMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableLongShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableObjectBooleanMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableObjectBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableObjectByteMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableObjectByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableObjectCharMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableObjectCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableObjectDoubleMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableObjectDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableObjectFloatMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableObjectFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableObjectIntMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableObjectIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableObjectLongMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableObjectLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableObjectShortMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableObjectShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableShortBooleanMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableShortBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableShortByteMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableShortByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableShortCharMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableShortCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableShortDoubleMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableShortDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableShortFloatMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableShortFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableShortIntMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableShortIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableShortLongMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableShortLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableShortObjectMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableShortObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.ImmutableShortShortMapFactory", "org.mapdb.collections.impl.map.immutable.primitive.ImmutableShortShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableBooleanBooleanMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableBooleanBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableBooleanByteMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableBooleanByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableBooleanCharMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableBooleanCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableBooleanDoubleMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableBooleanDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableBooleanFloatMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableBooleanFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableBooleanIntMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableBooleanIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableBooleanLongMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableBooleanLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableBooleanShortMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableBooleanShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableByteBooleanMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableByteBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableByteByteMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableByteByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableByteCharMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableByteCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableByteDoubleMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableByteDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableByteFloatMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableByteFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableByteIntMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableByteIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableByteLongMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableByteLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableByteObjectMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableByteObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableByteShortMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableByteShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableCharBooleanMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableCharBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableCharByteMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableCharByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableCharCharMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableCharCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableCharDoubleMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableCharDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableCharFloatMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableCharFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableCharIntMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableCharIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableCharLongMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableCharLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableCharObjectMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableCharObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableCharShortMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableCharShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableDoubleBooleanMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableDoubleBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableDoubleByteMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableDoubleByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableDoubleCharMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableDoubleCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableDoubleDoubleMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableDoubleDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableDoubleFloatMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableDoubleFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableDoubleIntMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableDoubleIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableDoubleLongMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableDoubleLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableDoubleObjectMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableDoubleObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableDoubleShortMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableDoubleShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableFloatBooleanMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableFloatBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableFloatByteMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableFloatByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableFloatCharMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableFloatCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableFloatDoubleMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableFloatDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableFloatFloatMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableFloatFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableFloatIntMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableFloatIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableFloatLongMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableFloatLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableFloatObjectMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableFloatObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableFloatShortMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableFloatShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableIntBooleanMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableIntBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableIntByteMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableIntByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableIntCharMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableIntCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableIntDoubleMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableIntDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableIntFloatMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableIntFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableIntIntMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableIntIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableIntLongMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableIntLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableIntObjectMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableIntObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableIntShortMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableIntShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableLongBooleanMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableLongBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableLongByteMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableLongByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableLongCharMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableLongCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableLongDoubleMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableLongDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableLongFloatMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableLongFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableLongIntMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableLongIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableLongLongMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableLongLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableLongObjectMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableLongObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableLongShortMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableLongShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectBooleanHashingStrategyMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectBooleanHashingStrategyMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectBooleanMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectByteHashingStrategyMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectByteHashingStrategyMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectByteMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectCharHashingStrategyMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectCharHashingStrategyMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectCharMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectDoubleHashingStrategyMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectDoubleHashingStrategyMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectDoubleMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectFloatHashingStrategyMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectFloatHashingStrategyMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectFloatMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectIntHashingStrategyMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectIntHashingStrategyMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectIntMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectLongHashingStrategyMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectLongHashingStrategyMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectLongMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectShortHashingStrategyMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectShortHashingStrategyMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableObjectShortMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableObjectShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableShortBooleanMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableShortBooleanMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableShortByteMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableShortByteMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableShortCharMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableShortCharMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableShortDoubleMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableShortDoubleMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableShortFloatMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableShortFloatMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableShortIntMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableShortIntMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableShortLongMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableShortLongMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableShortObjectMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableShortObjectMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.primitive.MutableShortShortMapFactory", "org.mapdb.collections.impl.map.mutable.primitive.MutableShortShortMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.sorted.ImmutableSortedMapFactory", "org.mapdb.collections.impl.map.sorted.immutable.ImmutableSortedMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.sorted.MutableSortedMapFactory", "org.mapdb.collections.impl.map.sorted.mutable.MutableSortedMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.map.ordered.MutableOrderedMapFactory", "org.mapdb.collections.impl.map.ordered.mutable.MutableOrderedMapFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.FixedSizeSetFactory", "org.mapdb.collections.impl.set.fixed.FixedSizeSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.ImmutableSetFactory", "org.mapdb.collections.impl.set.immutable.ImmutableSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.MultiReaderSetFactory", "org.mapdb.collections.impl.set.mutable.MultiReaderMutableSetFactory");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.MutableSetFactory", "org.mapdb.collections.impl.set.mutable.MutableSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.ImmutableBooleanSetFactory", "org.mapdb.collections.impl.set.immutable.primitive.ImmutableBooleanSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.ImmutableByteSetFactory", "org.mapdb.collections.impl.set.immutable.primitive.ImmutableByteSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.ImmutableCharSetFactory", "org.mapdb.collections.impl.set.immutable.primitive.ImmutableCharSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.ImmutableDoubleSetFactory", "org.mapdb.collections.impl.set.immutable.primitive.ImmutableDoubleSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.ImmutableFloatSetFactory", "org.mapdb.collections.impl.set.immutable.primitive.ImmutableFloatSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.ImmutableIntSetFactory", "org.mapdb.collections.impl.set.immutable.primitive.ImmutableIntSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.ImmutableLongSetFactory", "org.mapdb.collections.impl.set.immutable.primitive.ImmutableLongSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.ImmutableShortSetFactory", "org.mapdb.collections.impl.set.immutable.primitive.ImmutableShortSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.MutableBooleanSetFactory", "org.mapdb.collections.impl.set.mutable.primitive.MutableBooleanSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.MutableByteSetFactory", "org.mapdb.collections.impl.set.mutable.primitive.MutableByteSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.MutableCharSetFactory", "org.mapdb.collections.impl.set.mutable.primitive.MutableCharSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.MutableDoubleSetFactory", "org.mapdb.collections.impl.set.mutable.primitive.MutableDoubleSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.MutableFloatSetFactory", "org.mapdb.collections.impl.set.mutable.primitive.MutableFloatSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.MutableIntSetFactory", "org.mapdb.collections.impl.set.mutable.primitive.MutableIntSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.MutableLongSetFactory", "org.mapdb.collections.impl.set.mutable.primitive.MutableLongSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.primitive.MutableShortSetFactory", "org.mapdb.collections.impl.set.mutable.primitive.MutableShortSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.sorted.ImmutableSortedSetFactory", "org.mapdb.collections.impl.set.sorted.immutable.ImmutableSortedSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.set.sorted.MutableSortedSetFactory", "org.mapdb.collections.impl.set.sorted.mutable.MutableSortedSetFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.ImmutableStackFactory", "org.mapdb.collections.impl.stack.immutable.ImmutableStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.MutableStackFactory", "org.mapdb.collections.impl.stack.mutable.MutableStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.ImmutableBooleanStackFactory", "org.mapdb.collections.impl.stack.immutable.primitive.ImmutableBooleanStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.ImmutableByteStackFactory", "org.mapdb.collections.impl.stack.immutable.primitive.ImmutableByteStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.ImmutableCharStackFactory", "org.mapdb.collections.impl.stack.immutable.primitive.ImmutableCharStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.ImmutableDoubleStackFactory", "org.mapdb.collections.impl.stack.immutable.primitive.ImmutableDoubleStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.ImmutableFloatStackFactory", "org.mapdb.collections.impl.stack.immutable.primitive.ImmutableFloatStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.ImmutableIntStackFactory", "org.mapdb.collections.impl.stack.immutable.primitive.ImmutableIntStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.ImmutableLongStackFactory", "org.mapdb.collections.impl.stack.immutable.primitive.ImmutableLongStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.ImmutableShortStackFactory", "org.mapdb.collections.impl.stack.immutable.primitive.ImmutableShortStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.MutableBooleanStackFactory", "org.mapdb.collections.impl.stack.mutable.primitive.MutableBooleanStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.MutableByteStackFactory", "org.mapdb.collections.impl.stack.mutable.primitive.MutableByteStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.MutableCharStackFactory", "org.mapdb.collections.impl.stack.mutable.primitive.MutableCharStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.MutableDoubleStackFactory", "org.mapdb.collections.impl.stack.mutable.primitive.MutableDoubleStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.MutableFloatStackFactory", "org.mapdb.collections.impl.stack.mutable.primitive.MutableFloatStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.MutableIntStackFactory", "org.mapdb.collections.impl.stack.mutable.primitive.MutableIntStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.MutableLongStackFactory", "org.mapdb.collections.impl.stack.mutable.primitive.MutableLongStackFactoryImpl");
        FACTORY_IMPL.put("org.mapdb.collections.api.factory.stack.primitive.MutableShortStackFactory", "org.mapdb.collections.impl.stack.mutable.primitive.MutableShortStackFactoryImpl");
    }

    private ServiceLoaderUtils()
    {
        throw new AssertionError("Suppress default constructor for noninstantiability");
    }

    public static <T> T loadServiceClass(Class<T> serviceClass)
    {
        T result =
                ServiceLoaderUtils.loadServiceClass(serviceClass, Thread.currentThread().getContextClassLoader());
        if (result == null)
        {
            result = ServiceLoaderUtils.loadServiceClass(serviceClass, ServiceLoaderUtils.class.getClassLoader());
        }
        if (result == null)
        {
            result = ServiceLoaderUtils.loadByReflection(serviceClass, Thread.currentThread().getContextClassLoader());
        }
        if (result == null)
        {
            result = ServiceLoaderUtils.loadByReflection(serviceClass, ServiceLoaderUtils.class.getClassLoader());
        }
        if (result == null)
        {
            String message = "Could not find any implementations of "
                    + serviceClass.getSimpleName()
                    + ". Check that mapdb-collections.jar is on the classpath and that its META-INF/services directory is intact.";
            result = ServiceLoaderUtils.createProxyInstance(serviceClass, message);
        }
        return result;
    }

    private static <T> T loadServiceClass(Class<T> serviceClass, ClassLoader loader)
    {
        List<T> factories = new ArrayList<>();
        for (T factory : ServiceLoader.load(serviceClass, loader))
        {
            factories.add(factory);
        }
        if (factories.isEmpty())
        {
            return null;
        }
        if (factories.size() > 1)
        {
            String message = String.format(
                    "Found multiple implementations of %s on the classpath. Check that there is only one copy of mapdb-collections.jar on the classpath. Found implementations: %s.",
                    serviceClass.getSimpleName(),
                    factories.stream()
                            .map(T::getClass)
                            .map(Class::getSimpleName)
                            .collect(Collectors.joining(", ")));
            return ServiceLoaderUtils.createProxyInstance(serviceClass, message);
        }
        return factories.get(0);
    }

    private static <T> T loadByReflection(Class<T> serviceClass, ClassLoader loader)
    {
        String fallbackName = FACTORY_IMPL.get(serviceClass.getName());
        try
        {
            Class<T> fallbackClass = (Class<T>) Class.forName(fallbackName, true, loader);
            return fallbackClass.getDeclaredConstructor().newInstance();
        }
        catch (Exception e)
        {
            // ignore
        }
        return null;
    }

    private static <T> T createProxyInstance(Class<T> serviceClass, String message)
    {
        InvocationHandler handler = new ThrowingInvocationHandler(message);
        Object proxyInstance = Proxy.newProxyInstance(
                serviceClass.getClassLoader(),
                new Class[]{serviceClass},
                handler);
        return serviceClass.cast(proxyInstance);
    }
}
