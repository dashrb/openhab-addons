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

import static org.openhab.binding.blink.internal.BlinkBindingConstants.*;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.blink.internal.config.CameraConfiguration;
import org.openhab.binding.blink.internal.dto.BlinkAccount;
import org.openhab.binding.blink.internal.dto.BlinkCamera;
import org.openhab.binding.blink.internal.dto.BlinkEvents;
import org.openhab.binding.blink.internal.service.CameraService;
import org.openhab.binding.blink.internal.servlet.ThumbnailServlet;
import org.openhab.core.config.core.Configuration;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.library.types.DateTimeType;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.library.types.QuantityType;
import org.openhab.core.library.types.RawType;
import org.openhab.core.library.unit.ImperialUnits;
import org.openhab.core.library.unit.Units;
import org.openhab.core.net.NetworkAddressService;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.thing.binding.builder.ThingBuilder;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.osgi.service.http.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The {@link CameraHandler} is responsible for initializing camera thing and handling commands, which are
 * sent to one of the camera's channels.
 *
 * @author Matthias Oesterheld - Initial contribution
 * @author Robert T. Brown (-rb) - support Blink Authentication changes in 2025 (OAUTHv2)
 * @author Volker Bier - add support for Doorbells
 *
 */
@NonNullByDefault
public class CameraHandler extends BaseThingHandler implements EventListener {

    private final Logger logger = LoggerFactory.getLogger(CameraHandler.class);

    @NonNullByDefault({})
    CameraConfiguration config;
    @NonNullByDefault({})
    AccountHandler accountHandler;
    private final HttpService httpService;
    private final NetworkAddressService networkAddressService;
    CameraService cameraService;

    @Nullable
    ThumbnailServlet thumbnailServlet;

    String lastThumbnailPath = ""; // the homescreen thumbnail (comes from polling)
    // the most recent motion event / thumbnail, if any.
    BlinkEvents.@Nullable Media lastMotionEvent = null;

    DateTimeFormatter dateOnlyFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    public CameraHandler(Thing thing, HttpService httpService, NetworkAddressService networkAddressService,
            HttpClientFactory httpClientFactory, Gson gson) {
        super(thing);
        this.httpService = httpService;
        this.networkAddressService = networkAddressService;
        this.cameraService = new CameraService(httpClientFactory.getCommonHttpClient(), gson);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        logger.debug("Handling command {} {} for camera {}", channelUID.getId(), command.toFullString(),
                thing.getUID().getAsString());
        try {
            BlinkAccount account = accountHandler.getBlinkAccount();
            BlinkCamera currentState = accountHandler.getCameraState(config, false);
            if (CHANNEL_CAMERA_TEMPERATURE.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    double temp = currentState.signals.temp;
                    updateState(CHANNEL_CAMERA_TEMPERATURE, new QuantityType<>(temp, ImperialUnits.FAHRENHEIT));
                }
            } else if (CHANNEL_CAMERA_BATTERY.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_BATTERY, OnOffType.from(!"ok".equals(currentState.battery)));
                }
            } else if (CHANNEL_CAMERA_MOTIONDETECTION.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_MOTIONDETECTION, OnOffType.from(currentState.enabled));
                } else if (command instanceof OnOffType) {
                    OnOffType cmd = (OnOffType) command;
                    boolean enable = (cmd == OnOffType.ON);
                    if (config.cameraType == CameraConfiguration.CameraType.CAMERA) {
                        Long cmdId = cameraService.motionDetection(account, config, enable);
                        cameraService.watchCommandStatus(scheduler, account, config.networkId, cmdId,
                                this::asyncCommandFinished);
                    } else if (config.cameraType == CameraConfiguration.CameraType.DOORBELL) {
                        String result = cameraService.motionDetectionDoorbell(account, config, enable);
                        logger.debug("Returned from doorbell arm/disarm: {}", result); // {"id":200445162,"network_id":5xx2,"state":"done"}
                    } else {
                        String result = cameraService.motionDetectionOwl(account, config, enable);
                        logger.debug("Returned from owl arm/disarm: {}", result); // {"id":200445162,"network_id":5xx2,"state":"done"}
                    }
                }
            } else if (CHANNEL_CAMERA_SETTHUMBNAIL.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_SETTHUMBNAIL, OnOffType.OFF);
                } else if (command == OnOffType.ON) {
                    if (config.cameraType == CameraConfiguration.CameraType.CAMERA) {
                        Long cmdId = cameraService.createThumbnail(account, config);
                        cameraService.watchCommandStatus(scheduler, account, config.networkId, cmdId,
                                this::setThumbnailFinished);
                    } else if (config.cameraType == CameraConfiguration.CameraType.DOORBELL) {
                        String result = cameraService.createThumbnailDoorbell(account, config);
                        setThumbnailFinished(true);
                        logger.debug("Returned from doorbell createThumbnail: {}", result); // {"id":200445162,"network_id":5xx2,"command":"thumbnail","state":"new"}
                    } else {
                        String result = cameraService.createThumbnailOwl(account, config);
                        setThumbnailFinished(true);
                        logger.debug("Returned from owl createThumbnail: {}", result); // {"id":200445162,"network_id":5xx2,"command":"thumbnail","state":"new"}
                    }
                }
            } else if (CHANNEL_CAMERA_GETTHUMBNAIL.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    if (currentState.thumbnail != null) {
                        byte[] rawImage = getRawImage(currentState.thumbnail);
                        updateState(CHANNEL_CAMERA_GETTHUMBNAIL, new RawType(rawImage, "image/jpeg"));
                        lastThumbnailPath = currentState.thumbnail;
                    }
                }
            } else if (CHANNEL_CAMERA_LAST_UPDATED.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_LAST_UPDATED, DateTimeType.valueOf(currentState.updated_at));
                }
            } else if (CHANNEL_CAMERA_HIGH_USAGE_RATE.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_HIGH_USAGE_RATE, OnOffType.from(currentState.usage_rate));
                }
            } else if (CHANNEL_CAMERA_WIFI_LEVEL.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_WIFI_LEVEL, new DecimalType(currentState.signals.wifi));
                }
            } else if (CHANNEL_CAMERA_WIFI_RSSI.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_WIFI_RSSI, new DecimalType(currentState.details.signals.wifi_rssi));
                }
            } else if (CHANNEL_CAMERA_LFR_LEVEL.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_LFR_LEVEL, new DecimalType(currentState.signals.lfr));
                }
            } else if (CHANNEL_CAMERA_LFR_RSSI.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_CAMERA_LFR_RSSI, new DecimalType(currentState.details.signals.lfr_rssi));
                }
            }
        } catch (IOException e) {
            logger.warn("Camera {} command {} failed, going OFFLINE. Error was: {}", thing.getLabel(),
                    channelUID.getId(), e);
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Command Failed");
        }
    }

    public byte[] getThumbnail() throws IOException {
        byte[] rawImage = accountHandler.getMediaManager().getImage(lastThumbnailPath);
        return rawImage;
    }

    public byte[] getMotionThumbnail() throws IOException {
        BlinkEvents.Media event = lastMotionEvent;
        if (event == null) {
            throw new IOException("No motion thumbnail found for camera " + this.thing.getLabel());
        }
        return accountHandler.getMediaManager().getImage(event.id);
    }

    @Override
    public void initialize() {
        Configuration configuration = editConfiguration();
        if (!configuration.containsKey(PROPERTY_CAMERA_TYPE)) {
            logger.debug("CameraConfiguration: there is no camera type, adding 'CAMERA'.  uhhhh, why???");
            configuration.put(PROPERTY_CAMERA_TYPE, CameraConfiguration.CameraType.CAMERA);
            updateConfiguration(configuration);
        }
        config = getConfigAs(CameraConfiguration.class);
        logger.debug("Initializing camera {} as {}", thing.getUID().getAsString(), thing.getLabel());
        logger.trace("Read camera configuration {}", config);

        @Nullable
        Bridge bridge = getBridge();
        if (bridge == null || bridge.getHandler() == null) {
            logger.warn("Cannot handle commands of blink things without a bridge: {}", thing.getUID().getAsString());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "no bridge");
            return;
        }
        accountHandler = (AccountHandler) bridge.getHandler();

        if (thumbnailServlet == null) {
            try {
                logger.trace("Registering thumbnail servlet");
                thumbnailServlet = new ThumbnailServlet(httpService, this);
                Map<String, String> properties = editProperties();
                String scheme = "http://";
                int port = 8080;
                String imageUrl = scheme + networkAddressService.getPrimaryIpv4HostAddress() + ":" + port
                        + "/blink/thumbnail/" + thing.getUID().getId();
                properties.put("Thumbnail", imageUrl);
                logger.debug("Registered thumbnail servlet at {}", imageUrl);
                updateProperties(properties);
            } catch (IllegalStateException e) {
                logger.warn("Failed to create thumbnail servlet", e);
            }
        }

        // Adjust channels based on hardware variant
        switch (config.cameraType) {
            case CameraConfiguration.CameraType.CAMERA:
                // nothing to do yet
                break;
            case CameraConfiguration.CameraType.DOORBELL:
                removeChannelIfExists(CHANNEL_CAMERA_TEMPERATURE, "Doorbells do not report temperature");
                removeChannelIfExists(CHANNEL_CAMERA_BATTERY_VOLTAGE, "Doorbells do not report battery voltage");
                // removeChannelIfExists(CHANNEL_CAMERA_WIFI_LEVEL, "Doorbells do not report Wifi signal level");
                removeChannelIfExists(CHANNEL_CAMERA_WIFI_RSSI, "Doorbells do not report Wifi RSSI value");
                // removeChannelIfExists(CHANNEL_CAMERA_LFR_LEVEL, "Doorbells do not report Sync Module signal level");
                removeChannelIfExists(CHANNEL_CAMERA_LFR_RSSI, "Doorbells do not report Sync Module RSSI value");
                break;
            case CameraConfiguration.CameraType.OWL:
                removeChannelIfExists(CHANNEL_CAMERA_TEMPERATURE, "Minis/Owls do not report temperature");
                removeChannelIfExists(CHANNEL_CAMERA_BATTERY, "Minis/Owls do not use batteries");
                removeChannelIfExists(CHANNEL_CAMERA_BATTERY_VOLTAGE, "Minis/Owls do not use batteris");
                removeChannelIfExists(CHANNEL_CAMERA_WIFI_LEVEL, "Minis/Owls do not report Wifi signal level");
                removeChannelIfExists(CHANNEL_CAMERA_WIFI_RSSI, "Minis/Owls do not report Wifi RSSI value");
                removeChannelIfExists(CHANNEL_CAMERA_LFR_LEVEL, "Minis/Owls do not report Sync Module signal level");
                removeChannelIfExists(CHANNEL_CAMERA_LFR_RSSI, "Minis/Owls do not report Sync Module RSSI value");
                break;
        }

        // set the status to UNKNOWN temporarily and let the background refresh task decide the real status.
        updateStatus(ThingStatus.UNKNOWN);
    }

    private void removeChannelIfExists(String channelName, String logMessage) {
        ChannelUID candidate = new ChannelUID(this.thing.getUID(), channelName);
        if (thing.getChannel(candidate) != null) {
            logger.trace("Camera {}: removing channel {}: {}", thing.getLabel(), channelName, logMessage);
            ThingBuilder thingBuilder = editThing();
            thingBuilder.withoutChannel(candidate);
            updateThing(thingBuilder.build());
        }
    }

    @Override
    public void handleHomescreenUpdate() {
        if (config == null) {
            return;
        }
        logger.trace("Camera {} ({}) checking for state updates", thing.getLabel(), config.cameraId);
        try {
            BlinkAccount account = accountHandler.getBlinkAccount();
            BlinkCamera myCamera = accountHandler.getCameraState(config, false);

            if (config.cameraType == CameraConfiguration.CameraType.CAMERA) {
                myCamera.details = cameraService.getCameraDetails(account, config);
                updateState(CHANNEL_CAMERA_TEMPERATURE,
                        new QuantityType<>(myCamera.signals.temp, ImperialUnits.FAHRENHEIT));
                updateState(CHANNEL_CAMERA_BATTERY, OnOffType.from(!"ok".equals(myCamera.battery)));
                updateState(CHANNEL_CAMERA_BATTERY_VOLTAGE,
                        new QuantityType<>(myCamera.details.camera[0].battery_voltage / 100.0, Units.VOLT));
            } else if (config.cameraType == CameraConfiguration.CameraType.DOORBELL) {
                updateState(CHANNEL_CAMERA_BATTERY, OnOffType.from(!"ok".equals(myCamera.battery)));
            }
            updateState(CHANNEL_CAMERA_MOTIONDETECTION, OnOffType.from(myCamera.enabled));

            String imagePath = myCamera.thumbnail;
            if ((imagePath != null) && !imagePath.equals(lastThumbnailPath)) {
                logger.debug("Loading NEW thumbnail during refresh of camera {} ({})", thing.getLabel(),
                        config.cameraId);
                byte[] rawImage = getRawImage(imagePath);
                updateState(CHANNEL_CAMERA_GETTHUMBNAIL, new RawType(rawImage, "image/jpeg"));
                lastThumbnailPath = imagePath;
            }
            updateCameraProperties(myCamera);
            if ("offline".equals(myCamera.status)) {
                // hmmm, I observed that if the openhab user attempts some actions* on the camera,
                // they are sent to the blink servers, and blink updates its "updated_at" field,
                // since they think the camera itself made the request.
                // *not sure which actions. My dead battery camera has been dead for months and
                // this status reflected that for a while, but now it reflects two days ago. So
                // I clearly did *SOMETHING* that Blink counted as a sign of life for the camera.
                // TODO: figure out what changes this flag, and/or consider blocking certain
                // outgoing commands while the camera is offline.
                Instant when = Instant.parse(myCamera.updated_at);
                if (myCamera.details != null) {
                    // If it's a full camera (not a doorbell/owl), and I have these details,
                    // then use the battery_check_time field to estimate time of death.
                    if (myCamera.details.camera.length > 0 && myCamera.details.camera[0].battery_check_time != null) {
                        when = Instant.parse(myCamera.details.camera[0].battery_check_time);
                    }
                }
                updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                        "Offline, according to Blink, since " + dateOnlyFormatter.format(when));
            } else {
                updateStatus(ThingStatus.ONLINE);
            }
        } catch (IOException e) {
            lastThumbnailPath = "";
            // if the camera can't be updated, then set the camera itself offline.
            // One scenario why this happens is if the user deletes a camera in the Blink App--we still
            // have a CameraThing, but it can no longer be found in the "homescreen" list of legit cameras.
            // It also appears that cameras with dead batteries are no longer in Blink's list via the API,
            // although the Blink app shows dead cameras as "offline, click here to troubleshoot".
            // We will do the same--show it as OFFLINE (but we don't offer to help troubleshoot).
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Camera state could not be updated (e.g. battery dead / unplugged / deleted)");
        }
    }

    private void updateCameraProperties(BlinkCamera cam) {
        Map<String, String> props = editProperties();
        props.put("Date Added to Network", cam.created_at);
        props.put("Serial Number", cam.serial);
        props.put("Firmware Version", cam.fw_version);
        props.put("Camera Model", camTypeToUserString(cam.type));
        if (cam.revision != null) {
            props.put("Camera Revision", cam.revision);
        }
        props.put("Color", cam.color);
        if (cam.details != null) {
            props.put("MAC Address", cam.details.camera[0].mac_address);
            props.put("Last Battery Voltage Check", cam.details.camera[0].battery_check_time);
            props.put("First Boot", cam.details.camera[0].first_boot);
            props.put("IP Address", cam.details.camera[0].last_connect.ip_address);
            props.put("Network Error Count", "" + cam.details.camera[0].last_connect.socket_failure_count);
            props.put("Power Source", cam.details.camera[0].last_connect.ac_power ? "USB Power" : "Batteries");
        }
        updateProperties(props);
        // remove these old properties
        updateProperty("type", null);
        updateProperty("vendor", null);
        updateProperty("thumbnail", null);
        if (cam.details == null) {
            updateProperty("Power Source", null); // this was erroneously reported for doorbells, need to remove
        }
    }

    private static String camTypeToUserString(String type) {
        if ("white".equals(type)) {
            return "White, Indoor";
        }
        if ("xt".equals(type)) {
            return "Gen 1 (XT)";
        }
        if ("xt2".equals(type)) {
            return "Gen 2 (XT2)";
        }
        if ("catalina".equals(type)) {
            return "Gen 3 (catalina)";
        }
        if ("sedona".equals(type)) {
            return "Gen 4 (sedona)";
        }
        if ("owl".equals(type)) {
            return "Mini (owl)";
        }
        if ("hawk".equals(type)) {
            return "Mini 2 (hawk)";
        }
        if ("lotus".equals(type)) {
            return "Doorbell (lotus)";
        }
        return type;
    }

    @Override
    public void handleMediaEvent(BlinkEvents.Media mediaEvent) {
        if (!mediaEvent.isForThisCamera(config)) {
            return;
        }

        BlinkEvents.Media lastMotion = lastMotionEvent;
        if ((lastMotion == null) || (lastMotion.created_at.isBefore(mediaEvent.created_at))) {
            // The incoming motion event is newer than the most recent one we stored.
            // Store the new one, and update the motion thumbnail channel accordingly.
            String word = mediaEvent.isNewEvent() ? "New" : "New (OLD)";
            logger.debug("{}: {} motion thumbnail! {}", thing.getLabel(), word, mediaEvent.thumbnail);
            lastMotionEvent = mediaEvent;
            try {
                byte[] rawImage = accountHandler.getMediaManager().getImage(mediaEvent.id);
                updateState(CHANNEL_CAMERA_MOTIONTHUMBNAIL, new RawType(rawImage, "image/jpeg"));
            } catch (IOException e) {
                logger.debug("Failed to update thumbnail from recent motion event from {}.", mediaEvent.thumbnail, e);
            }
        }
        if (mediaEvent.isNewEvent()) {
            logger.debug("Triggering motion for camera {}, motion time={}", thing.getLabel(), mediaEvent.created_at);
            triggerChannel(CHANNEL_CAMERA_MOTIONTRIGGERED);
        }
    }

    @Override
    public void dispose() {
        logger.debug("Disposing camera service");
        cameraService.dispose();
        logger.debug("Trying to dispose thumbnail servlet");
        disposeServlet();
        super.dispose();
    }

    private void disposeServlet() {
        ThumbnailServlet servlet = this.thumbnailServlet;
        if (servlet != null) {
            logger.debug("Disposing thumbnail servlet");
            servlet.dispose();
        }
        this.thumbnailServlet = null;
    }

    private void setThumbnailFinished(boolean success) {
        updateState(CHANNEL_CAMERA_SETTHUMBNAIL, OnOffType.OFF);
        asyncCommandFinished(success);
    }

    private void asyncCommandFinished(boolean success) {
        if (success) {
            accountHandler.getDevices(true); // trigger refresh of homescreen
        }
    }

    private byte[] getRawImage(String imagePath) throws IOException {
        return accountHandler.getMediaManager().getImage(imagePath);
    }
}
