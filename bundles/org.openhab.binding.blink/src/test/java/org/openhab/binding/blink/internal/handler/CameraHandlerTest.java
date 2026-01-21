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
package org.openhab.binding.blink.internal.handler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.openhab.binding.blink.internal.BlinkTestUtil;
import org.openhab.binding.blink.internal.config.CameraConfiguration;
import org.openhab.binding.blink.internal.config.CameraConfiguration.CameraType;
import org.openhab.binding.blink.internal.dto.BlinkAccount;
import org.openhab.binding.blink.internal.dto.BlinkCamera;
import org.openhab.binding.blink.internal.service.CameraService;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusInfo;
import org.openhab.core.thing.ThingTypeUID;
import org.openhab.core.thing.ThingUID;
import org.openhab.core.thing.binding.ThingHandlerCallback;
import org.openhab.core.thing.internal.ThingImpl;
import org.openhab.core.types.RefreshType;
import org.openhab.core.types.State;
import org.osgi.service.http.HttpService;

import com.google.gson.Gson;

/**
 * Test class.
 *
 * @author Matthias Oesterheld - Initial contribution
 */
@ExtendWith(MockitoExtension.class)
@NonNullByDefault
public class CameraHandlerTest {

    private static final String CAMERA_ID = "123";
    private static final String NETWORK_ID = "567";
    static final ThingTypeUID THING_TYPE_UID = new ThingTypeUID("blink", "camera");
    private static final ChannelUID CHANNEL_CAMERA_TEMPERATURE = new ChannelUID(new ThingUID(THING_TYPE_UID, CAMERA_ID),
            "temperature");
    private static final ChannelUID CHANNEL_CAMERA_BATTERY = new ChannelUID(new ThingUID(THING_TYPE_UID, CAMERA_ID),
            "battery");
    private static final ChannelUID CHANNEL_CAMERA_MOTIONDETECTION = new ChannelUID(
            new ThingUID(THING_TYPE_UID, CAMERA_ID), "motiondetection");
    private static final ChannelUID CHANNEL_CAMERA_SETTHUMBNAIL = new ChannelUID(
            new ThingUID(THING_TYPE_UID, CAMERA_ID), "setThumbnail");
    private static final ChannelUID CHANNEL_CAMERA_GETTHUMBNAIL = new ChannelUID(
            new ThingUID(THING_TYPE_UID, CAMERA_ID), "getThumbnail");
    @NonNullByDefault({})
    CameraHandler cameraHandler;
    @Mock
    @NonNullByDefault({})
    ThingHandlerCallback callback;

    @Spy
    Thing thing = new ThingImpl(THING_TYPE_UID, CAMERA_ID);
    @Mock
    @NonNullByDefault({})
    HttpClientFactory httpClientFactory;
    @Mock
    @NonNullByDefault({})
    HttpService httpService;
    @Mock
    @NonNullByDefault({})
    NetworkAddressService networkAddressService;
    @Mock
    @NonNullByDefault({})
    Bridge account;
    @Mock
    @NonNullByDefault({})
    AccountHandler accountHandler;
    @Mock
    @NonNullByDefault({})
    CameraService cameraService;

    private BlinkCamera getCameraTestInstance() {
        BlinkCamera cam = new BlinkCamera(123L, 567L);
        cam.battery = "low";
        cam.color = "black";
        cam.created_at = "2026-01-01T00:01:23+00:00";
        cam.enabled = true;
        cam.fw_version = "1.1";
        cam.name = "Test Camera";
        cam.revision = null;
        cam.serial = "XYZ123";
        cam.status = "done";
        cam.thumbnail = "thumbnail-url";
        cam.type = CameraType.CAMERA.toString();
        cam.updated_at = "2026-01-16T02:14:43+00:00";
        cam.usage_rate = false;
        cam.signals = cam.new Signals();
        cam.signals.battery = 2;
        cam.signals.lfr = 4;
        cam.signals.wifi = 5;
        cam.signals.temp = 73;
        cam.details = cam.new Details();
        cam.details.signals = cam.new SignalsDetails();
        cam.details.signals.lfr = 4;
        cam.details.signals.lfr_rssi = -40;
        cam.details.signals.wifi = 5;
        cam.details.signals.wifi_rssi = -35;
        cam.details.camera = new BlinkCamera.HardwareDetails[1];
        cam.details.camera[0] = cam.new HardwareDetails();
        cam.details.camera[0].battery_check_time = "2026-01-16T01:14:43+00:00";
        cam.details.camera[0].battery_voltage = 163;
        cam.details.camera[0].first_boot = "2020-03-05T12:14:43+00:00";
        cam.details.camera[0].mac_address = "aa:bb:ee:ff:11:22:33:44";
        cam.details.camera[0].last_connect = cam.new ConnectionDetails();
        cam.details.camera[0].last_connect.ac_power = false;
        cam.details.camera[0].last_connect.ip_address = "192.168.1.199";
        cam.details.camera[0].last_connect.socket_failure_count = 0;
        return cam;
    }

    @BeforeEach
    void setup() {
        when(httpClientFactory.getCommonHttpClient()).thenReturn(new HttpClient());
        Configuration config = new Configuration();
        config.put("cameraId", CAMERA_ID);
        config.put("networkId", NETWORK_ID);
        config.put("cameraType", CameraConfiguration.CameraType.CAMERA.toString());
        when(thing.getConfiguration()).thenReturn(config);
        doReturn(accountHandler).when(account).getHandler();
        cameraHandler = spy(
                new CameraHandler(thing, httpService, networkAddressService, httpClientFactory, new Gson()) {
                    @Override
                    protected @Nullable Bridge getBridge() {
                        return account;
                    }
                });
        cameraHandler.setCallback(callback);
        cameraHandler.initialize();
    }

    @Test
    void testInitialize() {
        assertThat(cameraHandler.config, is(notNullValue()));
        assertThat(cameraHandler.config.cameraId, is(Long.parseLong(CAMERA_ID)));
        assertThat(cameraHandler.config.networkId, is(Long.parseLong(NETWORK_ID)));
        assertThat(cameraHandler.config.cameraType, is(CameraConfiguration.CameraType.CAMERA));
        ArgumentCaptor<ThingStatusInfo> statusCaptor = ArgumentCaptor.forClass(ThingStatusInfo.class);
        verify(callback).statusUpdated(eq(thing), statusCaptor.capture());
        assertThat(statusCaptor.getValue().getStatus(), is(ThingStatus.UNKNOWN));
    }

    @Test
    void testSetOfflineOnHandleCommandException() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        doThrow(IOException.class).when(accountHandler).getCameraState(any(), eq(false));
        cameraHandler.handleCommand(CHANNEL_CAMERA_TEMPERATURE, RefreshType.REFRESH);
        ArgumentCaptor<ThingStatusInfo> statusCaptor = ArgumentCaptor.forClass(ThingStatusInfo.class);
        verify(callback, atLeastOnce()).statusUpdated(eq(thing), statusCaptor.capture());
        assertThat(statusCaptor.getValue().getStatus(), is(ThingStatus.OFFLINE));
        assertThat(cameraHandler.lastThumbnailPath, is(emptyString()));
    }

    @Test
    void testRefreshTemperatureChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        double toBeReturned = 69.0;
        BlinkCamera camera = this.getCameraTestInstance();
        camera.signals.temp = toBeReturned;
        doReturn(camera).when(accountHandler).getCameraState(any(), eq(false));
        cameraHandler.handleCommand(CHANNEL_CAMERA_TEMPERATURE, RefreshType.REFRESH);
        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        verify(callback).stateUpdated(eq(CHANNEL_CAMERA_TEMPERATURE), stateCaptor.capture());
        assertThat(stateCaptor.getValue(), is(new QuantityType<>(toBeReturned, ImperialUnits.FAHRENHEIT)));
    }

    @Test
    void testRefreshBatteryChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        BlinkCamera camera = this.getCameraTestInstance();
        camera.battery = "ok"; // different value from original
        doReturn(camera).when(accountHandler).getCameraState(any(), eq(false));
        cameraHandler.handleCommand(CHANNEL_CAMERA_BATTERY, RefreshType.REFRESH);
        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        verify(callback).stateUpdated(eq(CHANNEL_CAMERA_BATTERY), stateCaptor.capture());
        assertThat(stateCaptor.getValue(), is(OnOffType.OFF));
    }

    @Test
    void testRefreshMotionDetectionChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        BlinkCamera camera = this.getCameraTestInstance();
        camera.enabled = false;
        doReturn(camera).when(accountHandler).getCameraState(any(), eq(false));
        cameraHandler.handleCommand(CHANNEL_CAMERA_MOTIONDETECTION, RefreshType.REFRESH);
        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        verify(callback).stateUpdated(eq(CHANNEL_CAMERA_MOTIONDETECTION), stateCaptor.capture());
        assertThat(stateCaptor.getValue(), is(OnOffType.OFF));
    }

    @Test
    void testOnOffMotionDetectionChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        BlinkAccount blinkAccount = BlinkTestUtil.testBlinkAccount();
        doReturn(blinkAccount).when(accountHandler).getBlinkAccount();
        CameraService cameraService = mock(CameraService.class);
        cameraHandler.cameraService = cameraService;
        doReturn(123L).when(cameraService).motionDetection(ArgumentMatchers.any(BlinkAccount.class),
                ArgumentMatchers.any(CameraConfiguration.class), anyBoolean());
        cameraHandler.handleCommand(CHANNEL_CAMERA_MOTIONDETECTION, OnOffType.ON);
        CameraConfiguration handlerConfig = cameraHandler.config;
        CameraConfiguration config = (handlerConfig == null) ? new CameraConfiguration() : handlerConfig;
        verify(cameraService).motionDetection(blinkAccount, config, true);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Boolean>> handlerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(cameraService).watchCommandStatus(any(), same(blinkAccount), any(), any(), handlerCaptor.capture());
        handlerCaptor.getValue().accept(true);
        // check if correct handler is called (bug in commit 767f08f7)
        verify(callback, times(0)).stateUpdated(eq(CHANNEL_CAMERA_SETTHUMBNAIL), any());
        verify(accountHandler).getDevices(true);
    }

    @Test
    void testSetThumbnailChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        BlinkAccount blinkAccount = BlinkTestUtil.testBlinkAccount();
        doReturn(blinkAccount).when(accountHandler).getBlinkAccount();
        CameraService cameraService = mock(CameraService.class);
        cameraHandler.cameraService = cameraService;
        doReturn(123L).when(cameraService).createThumbnail(ArgumentMatchers.any(BlinkAccount.class),
                ArgumentMatchers.any(CameraConfiguration.class));
        cameraHandler.handleCommand(CHANNEL_CAMERA_SETTHUMBNAIL, OnOffType.ON);
        CameraConfiguration handlerConfig = cameraHandler.config;
        CameraConfiguration config = (handlerConfig == null) ? new CameraConfiguration() : handlerConfig;
        verify(cameraService).createThumbnail(blinkAccount, config);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Boolean>> handlerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(cameraService).watchCommandStatus(any(), same(blinkAccount), any(), any(), handlerCaptor.capture());
        handlerCaptor.getValue().accept(true);
        verify(callback, atLeastOnce()).stateUpdated(eq(CHANNEL_CAMERA_SETTHUMBNAIL), eq(OnOffType.OFF));
        verify(accountHandler, atLeastOnce()).getDevices(true);
    }

    @Test
    void testGetThumbnailChannel() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        BlinkAccount blinkAccount = BlinkTestUtil.testBlinkAccount();
        doReturn(blinkAccount).when(accountHandler).getBlinkAccount();
        CameraService cameraService = mock(CameraService.class);
        cameraHandler.cameraService = cameraService;
        BlinkCamera camera = this.getCameraTestInstance();
        doReturn(camera).when(accountHandler).getCameraState(ArgumentMatchers.any(CameraConfiguration.class),
                eq(false));
        byte[] bytes = "expected".getBytes(StandardCharsets.UTF_8);
        RawType expected = new RawType(bytes, "image/jpeg");
        doReturn(bytes).when(cameraService).getThumbnail(ArgumentMatchers.any(BlinkAccount.class), anyString());
        cameraHandler.handleCommand(CHANNEL_CAMERA_GETTHUMBNAIL, RefreshType.REFRESH);
        CameraConfiguration handlerConfig = cameraHandler.config;
        CameraConfiguration config = (handlerConfig == null) ? new CameraConfiguration() : handlerConfig;
        verify(accountHandler).getCameraState(config, false);
        verify(cameraService).getThumbnail(blinkAccount, camera.thumbnail);
        ArgumentCaptor<State> stateCaptor = ArgumentCaptor.forClass(State.class);
        verify(callback).stateUpdated(eq(CHANNEL_CAMERA_GETTHUMBNAIL), stateCaptor.capture());
        assertThat(stateCaptor.getValue(), is(expected));
    }

    @Test
    void testDispose() {
        cameraHandler.cameraService = cameraService;
        cameraHandler.dispose();
        verify(cameraService).dispose();
    }

    @Test
    void testHandleHomescreenUpdate() throws IOException {
        cameraHandler.accountHandler = accountHandler;
        cameraHandler.cameraService = cameraService;
        doReturn(BlinkTestUtil.testBlinkAccount()).when(accountHandler).getBlinkAccount();
        BlinkCamera camera = this.getCameraTestInstance();
        doReturn(camera).when(accountHandler).getCameraState(any(), eq(false));
        doReturn(camera.details).when(cameraService).getCameraDetails(any(), any());
        cameraHandler.handleHomescreenUpdate();
        verify(callback).stateUpdated(CHANNEL_CAMERA_TEMPERATURE, new QuantityType<>(73.0, ImperialUnits.FAHRENHEIT));
        verify(callback).stateUpdated(CHANNEL_CAMERA_BATTERY, OnOffType.ON);
        verify(callback).stateUpdated(CHANNEL_CAMERA_MOTIONDETECTION, OnOffType.ON);
        verify(accountHandler).getCameraState(any(), eq(false));
    }

    @Test
    void testHandleHomescreenUpdateOnException() throws IOException {
        cameraHandler.cameraService = cameraService;
        doThrow(IOException.class).when(cameraService).getCameraDetails(any(), any());
        cameraHandler.handleHomescreenUpdate();
        ArgumentCaptor<ThingStatusInfo> statusCaptor = ArgumentCaptor.forClass(ThingStatusInfo.class);
        verify(callback, atLeastOnce()).statusUpdated(eq(thing), statusCaptor.capture());
        assertThat(statusCaptor.getValue().getStatus(), is(ThingStatus.OFFLINE));

        assertThat(cameraHandler.lastThumbnailPath, is(emptyString()));
    }
}
