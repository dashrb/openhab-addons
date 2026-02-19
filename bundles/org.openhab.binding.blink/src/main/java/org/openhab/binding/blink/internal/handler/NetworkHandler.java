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
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.blink.internal.config.NetworkConfiguration;
import org.openhab.binding.blink.internal.dto.BlinkAccount;
import org.openhab.binding.blink.internal.dto.BlinkNetwork;
import org.openhab.binding.blink.internal.dto.BlinkSyncModule;
import org.openhab.binding.blink.internal.service.NetworkService;
import org.openhab.core.io.net.http.HttpClientFactory;
import org.openhab.core.library.types.DecimalType;
import org.openhab.core.library.types.OnOffType;
import org.openhab.core.thing.Bridge;
import org.openhab.core.thing.ChannelUID;
import org.openhab.core.thing.Thing;
import org.openhab.core.thing.ThingStatus;
import org.openhab.core.thing.ThingStatusDetail;
import org.openhab.core.thing.binding.BaseThingHandler;
import org.openhab.core.types.Command;
import org.openhab.core.types.RefreshType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;

/**
 * The {@link NetworkHandler} is responsible for initializing network things and handling commands, which are
 * sent to one of the network's channels.
 *
 * @author Matthias Oesterheld - Initial contribution
 * @author Robert T. Brown (-rb) - support Blink Authentication changes in 2025 (OAUTHv2)
 */
@NonNullByDefault
public class NetworkHandler extends BaseThingHandler implements EventListener {

    private final Logger logger = LoggerFactory.getLogger(NetworkHandler.class);

    @NonNullByDefault({})
    NetworkConfiguration config;
    @NonNullByDefault({})
    AccountHandler accountHandler;
    NetworkService networkService;

    private @Nullable BlinkNetwork currentNetworkState;
    private @Nullable BlinkSyncModule currentSyncModuleState;

    public NetworkHandler(Thing thing, HttpClientFactory httpClientFactory, Gson gson) {
        super(thing);
        networkService = new NetworkService(httpClientFactory.getCommonHttpClient(), gson);
    }

    @Override
    public void handleCommand(ChannelUID channelUID, Command command) {
        try {
            BlinkNetwork current = accountHandler.getNetworkState(config.networkId, false);
            BlinkSyncModule sync = accountHandler.getSyncModuleState(config.networkId);
            if (CHANNEL_NETWORK_ARMED.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    updateState(CHANNEL_NETWORK_ARMED, OnOffType.from(current.armed));
                } else if (command instanceof OnOffType) {
                    OnOffType cmd = (OnOffType) command;
                    boolean enable = (cmd == OnOffType.ON);
                    Long cmdId = networkService.arm(accountHandler.getBlinkAccount(), Long.toString(config.networkId),
                            enable);
                    networkService.watchCommandStatus(scheduler, accountHandler.getBlinkAccount(), config.networkId,
                            cmdId, this::asyncCommandFinished);
                }
            } else if (CHANNEL_NETWORK_WIFI_LEVEL.equals(channelUID.getId())) {
                if (command instanceof RefreshType) {
                    if (sync != null) {
                        updateState(CHANNEL_NETWORK_WIFI_LEVEL, new DecimalType(sync.wifi_strength));
                    }
                }
            }
        } catch (IOException e) {
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR, "Command Failed");
        }
    }

    @Override
    public void initialize() {
        config = getConfigAs(NetworkConfiguration.class);
        logger.debug("Initializing network {}", thing.getUID().getAsString());
        @Nullable
        Bridge bridge = getBridge();
        if (bridge == null || bridge.getHandler() == null) {
            logger.warn("Cannot handle commands of blink things without a bridge: {}", thing.getUID().getAsString());
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.CONFIGURATION_ERROR, "no bridge");
            return;
        }
        accountHandler = (AccountHandler) bridge.getHandler();
        // set the status to UNKNOWN temporarily and let the background refresh task decide the real status.
        updateStatus(ThingStatus.UNKNOWN); // keep it unknown until refreshState() gets info from Blink.
    }

    @Override
    public void handleHomescreenUpdate() {
        BlinkAccount account = accountHandler.getBlinkAccount();
        try {
            logger.trace("Sync Module {} (Network {}) checking for state updates", thing.getLabel(), config.networkId);
            BlinkNetwork current = accountHandler.getNetworkState(config.networkId, false);
            current.details = networkService.getDetails(account, config);
            currentNetworkState = current;

            BlinkSyncModule sync = accountHandler.getSyncModuleState(config.networkId);
            if (sync != null) {
                sync.details = networkService.getSyncModuleDetails(account, config.networkId);
                updateState(CHANNEL_NETWORK_WIFI_LEVEL, new DecimalType(sync.wifi_strength));
            }
            currentSyncModuleState = sync;
            updateState(CHANNEL_NETWORK_ARMED, OnOffType.from(current.armed));
            updateStatus(ThingStatus.ONLINE);

            // update properties
            updateNetworkProperties();

        } catch (IOException e) {
            // accountHandler.setOffline(e);
            // if the network (aka sync module) can't be updated, then set the network itself offline.
            // One scenario why this happens is if the user deletes a sync module in the Blink App--we still
            // have a NetworkThing, but it can no longer be found in the "homescreen" list of legit networks.
            // Show it as OFFLINE.
            updateStatus(ThingStatus.OFFLINE, ThingStatusDetail.COMMUNICATION_ERROR,
                    "Network (sync module) state could not be updated (e.g. unplugged / deleted)");
        }
    }

    @Override
    public void dispose() {
        networkService.dispose();
        super.dispose();
    }

    private void asyncCommandFinished(boolean success) {
        if (success) {
            accountHandler.getDevices(true); // trigger refresh of homescreen
        }
    }

    private void updateNetworkProperties() {
        BlinkNetwork netState = currentNetworkState;
        if (netState != null) {
            Map<String, String> newProps = editProperties();
            newProps.put("Video Destination", netState.details.network.video_destination);
            BlinkSyncModule sync = currentSyncModuleState;
            if (sync != null) {
                newProps.put("Sync Module Serial Number", sync.serial);
                newProps.put("Sync Module Firmware Version", sync.fw_version);
                newProps.put("Sync Module Type", syncModuleTypeToUserString(sync.type, sync.subtype));
                newProps.put("First Boot", sync.details.syncmodule.first_boot);
                newProps.put("Local Storage Compatible?", sync.local_storage_compatible ? "True" : "False");
                if (sync.local_storage_compatible) {
                    newProps.put("Local Storage Enabled?", sync.local_storage_enabled ? "True" : "False");
                    newProps.put("Local Storage Status", sync.local_storage_status);
                    newProps.put("Storage Used", String.valueOf(netState.details.network.storage_used));
                    newProps.put("Storage Total", String.valueOf(netState.details.network.storage_total));
                }
            }
            updateProperties(newProps);
            updateProperty("vendor", null);
        }
    }

    private String syncModuleTypeToUserString(String type, String subtype) {
        if ("sm1".equals(type)) {
            return "Gen 1";
        }
        if ("sm2".equals(type)) {
            return "Gen 2 (subtype: " + subtype + ")";
        }
        if ("kalahari".equals(type)) {
            return "Sync Module Core (kalahari)";
        }
        return "Sync Module XR? (" + type + ")";
    }
}
