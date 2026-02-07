/**
 * Copyright (c) 2010-2023 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */
package org.openhab.binding.blink.internal;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.storage.Storage;
import org.openhab.core.storage.StorageService;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.binding.ThingHandler;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.service.http.HttpService;

/**
 * Test class.
 *
 * @author Matthias Oesterheld - Initial contribution
 */
@NonNullByDefault
@ExtendWith(MockitoExtension.class)
class BlinkHandlerFactoryTest {

    private static final String BINDING_NAME = "blink";
    private static final String ACCOUNT = "account";
    private static final String CAMERA = "camera";
    private static final String NETWORK = "network";
    private @Mock @NonNullByDefault({}) HttpService httpService;
    private @Mock @NonNullByDefault({}) HttpClientFactory mockHttpClientFactory;
    private @Mock @NonNullByDefault({}) HttpClient mockHttpClient;
    private @Mock @NonNullByDefault({}) NetworkAddressService networkAddressService;
    private @Mock @NonNullByDefault({}) StorageService mockStorageService;
    private @Mock @NonNullByDefault({}) BundleContext mockBundleContext;
    private @Mock @NonNullByDefault({}) Bundle mockBundle;
    @SuppressWarnings("null")
    private @Mock @NonNullByDefault({}) Storage<Object> storage = BlinkTestUtil.testStorage();

    private BlinkHandlerFactory factory = new BlinkHandlerFactory(httpService, mockHttpClientFactory,
            networkAddressService, mockStorageService) {
        @Override
        protected BundleContext getBundleContext() {
            return mockBundleContext;
        }
    };

    static List<@Nullable String> thingUIDs() {
        ArrayList<@Nullable String> uids = new ArrayList<>();
        uids.add(ACCOUNT);
        uids.add(CAMERA);
        uids.add(NETWORK);
        uids.add(null);
        return uids;
    }

    void setupMocks() {
        // when(httpClientFactory.getCommonHttpClient()).thenReturn(mockHttpClient);
        // factory = new BlinkHandlerFactory(httpService, httpClientFactory, networkAddressService, storageService) {
        // @Override
        // protected BundleContext getBundleContext() {
        // return bundleContext;
        // }
        // };
    }

    @Test
    void supportsGivenNumberOfThings() {
        assertThat(BlinkHandlerFactory.SUPPORTED_THING_TYPES_UIDS.size(), is(thingUIDs().size() - 1));
    }

    @ParameterizedTest
    @MethodSource("thingUIDs")
    void supportsCorrectThingType(@Nullable String uid) {
        ThingTypeUID thingTypeUID = (uid == null) ? new ThingTypeUID(BINDING_NAME, "hurz")
                : new ThingTypeUID(BINDING_NAME, uid);
        assertThat(factory.supportsThingType(thingTypeUID), is(uid != null));
    }

    private @Mock @NonNullByDefault({}) Thing thing;

    @Test
    void createHandler_unknown() {
        when(thing.getThingTypeUID()).thenReturn(new ThingTypeUID(BINDING_NAME, "hurz"));
        ThingHandler handler = factory.createHandler(thing);
        assertThat(handler, is(nullValue()));
    }
}
