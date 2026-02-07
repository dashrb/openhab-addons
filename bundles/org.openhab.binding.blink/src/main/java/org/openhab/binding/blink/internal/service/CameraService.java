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
package org.openhab.binding.blink.internal.service;

import java.io.IOException;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.http.HttpMethod;
import org.openhab.binding.blink.internal.config.CameraConfiguration;
import org.openhab.binding.blink.internal.dto.BlinkAccount;
import org.openhab.binding.blink.internal.dto.BlinkCamera;
import org.openhab.binding.blink.internal.dto.BlinkCommand;

import com.google.gson.Gson;

/**
 * The {@link CameraService} class handles all communication with camera related blink apis.
 *
 * @author Matthias Oesterheld - Initial contribution
 * @author Robert T. Brown (-rb) - support Blink Authentication changes in 2025 (OAUTHv2)
 * @author Volker Bier - add support for Doorbells
 *
 */
@NonNullByDefault
public class CameraService extends BaseBlinkApiService {
    public CameraService(HttpClient httpClient, Gson gson) {
        super(httpClient, gson);
    }

    /**
     * Call motion detection endpoints do enable/disable motion detection for the given camera.
     *
     * @param account blink account
     * @param camera blink camera
     * @param enable enable/disable
     * @return blink async command ID
     */
    public Long motionDetection(@Nullable BlinkAccount account, @Nullable CameraConfiguration camera, boolean enable)
            throws IOException {
        if (account == null || account.account == null || camera == null) {
            throw new IllegalArgumentException("This Blink Account is not authenticated yet");
        }
        String action = (enable) ? "/enable" : "/disable";
        String uri = "/network/" + camera.networkId + "/camera/" + camera.cameraId + action;
        BlinkCommand cmd = apiRequest(account.account.tier, uri, HttpMethod.POST, account.auth.access_token, null,
                BlinkCommand.class);
        return cmd.id;
    }

    /**
     * Call API to do enable/disable motion detection for the given Doorbell camera.
     *
     * @param account blink account
     * @param camera blink camera
     * @param enable enable/disable
     * @return blink JSON string reflecting the status, e.g. {"id":200445162,"network_id":5xx2,"state":"done"}
     */
    public String motionDetectionDoorbell(@Nullable BlinkAccount account, @Nullable CameraConfiguration camera,
            boolean enable) throws IOException {
        if (account == null || account.account == null || camera == null) {
            throw new IllegalArgumentException("This Blink Account is not authenticated yet");
        }
        String action = (enable) ? "/enable" : "/disable";
        String uri = "/api/v1/accounts/" + account.account.account_id + "/networks/" + camera.networkId + "/doorbells/"
                + camera.cameraId + action;
        return request(account.account.tier, uri, HttpMethod.POST, account.auth.access_token, null, null);
    }

    /**
     * Call API to do enable/disable motion detection for the given OWL-type (aka mini) camera.
     *
     * @param account blink account
     * @param camera blink camera
     * @param enable enable/disable
     * @return blink JSON string reflecting the status, e.g. {"id":200445162,"network_id":5xx2,"state":"done"}
     */
    public String motionDetectionOwl(@Nullable BlinkAccount account, @Nullable CameraConfiguration camera,
            boolean enable) throws IOException {
        if (account == null || account.account == null || camera == null) {
            throw new IllegalArgumentException("This Blink Account is not authenticated yet");
        }
        String command = "{\"enabled\": " + enable + "}";
        String uri = "/api/v1/accounts/" + account.account.account_id + "/networks/" + camera.networkId + "/owls/"
                + camera.cameraId + "/config";
        return request(account.account.tier, uri, HttpMethod.POST, account.auth.access_token, null, command);
    }

    /**
     * Call thumbnail endpoint to create a thumbnail for the given camera.
     *
     * @param account blink account
     * @param camera blink camera
     * @return blink async command ID
     */
    public Long createThumbnail(@Nullable BlinkAccount account, @Nullable CameraConfiguration camera)
            throws IOException {
        if (account == null || account.account == null || camera == null) {
            throw new IllegalArgumentException("This Blink Account is not authenticated yet");
        }
        String uri = "/network/" + camera.networkId + "/camera/" + camera.cameraId + "/thumbnail";
        BlinkCommand cmd = apiRequest(account.account.tier, uri, HttpMethod.POST, account.auth.access_token, null,
                BlinkCommand.class);
        return cmd.id;
    }

    /**
     * Call thumbnail endpoint to create a thumbnail for the given doorbell camera.
     *
     * @param account blink account
     * @param camera blink camera
     * @return JSON indicating the status of the request, e.g.
     *         {"id":200445162,"network_id":5xx2,"command":"thumbnail","state":"new"}
     */
    public String createThumbnailDoorbell(@Nullable BlinkAccount account, @Nullable CameraConfiguration camera)
            throws IOException {
        if (account == null || account.account == null || camera == null) {
            throw new IllegalArgumentException("This Blink Account is not authenticated yet");
        }
        String command = "";
        String uri = "/api/v1/accounts/" + account.account.account_id + "/networks/" + camera.networkId + "/doorbells/"
                + camera.cameraId + "/thumbnail";
        return request(account.account.tier, uri, HttpMethod.POST, account.auth.access_token, null, command);
    }

    /**
     * Call thumbnail endpoint to create a thumbnail for the given OWL-type (aka mini) camera.
     *
     * @param account blink account
     * @param camera blink camera
     * @return JSON indicating the status of the request, e.g.
     *         {"id":200445162,"network_id":5xx2,"command":"thumbnail","state":"new"}
     */
    public String createThumbnailOwl(@Nullable BlinkAccount account, @Nullable CameraConfiguration camera)
            throws IOException {
        if (account == null || account.account == null || camera == null) {
            throw new IllegalArgumentException("This Blink Account is not authenticated yet");
        }
        String command = "";
        String uri = "/api/v1/accounts/" + account.account.account_id + "/networks/" + camera.networkId + "/owls/"
                + camera.cameraId + "/thumbnail";
        return request(account.account.tier, uri, HttpMethod.POST, account.auth.access_token, null, command);
    }

    /**
     * This method returns significant details about the Camera, in a CameraDetails DTO.
     * WARNING: Calling this function with a Doorbell or Mini will result in a IOException (404 not found)
     * as the Blink system does not support this API endpoint with those lesser camera devices.
     *
     * @param account
     * @param camera
     * @throws IOException
     */
    public BlinkCamera.Details getCameraDetails(@Nullable BlinkAccount account, CameraConfiguration camera)
            throws IOException {
        if (account == null || account.account == null) {
            throw new IllegalArgumentException("This Blink Account is not authenticated yet");
        }
        String uri = "/network/" + camera.networkId + "/camera/" + camera.cameraId + "/config";
        BlinkCamera.Details details = apiRequest(account.account.tier, uri, HttpMethod.GET, account.auth.access_token,
                null, BlinkCamera.Details.class);
        return details;
    }

    /**
     * This doesn't appear to have anything additional over the homescreen sensor info.
     *
     * @param account
     * @param camera
     * @throws IOException
     */
    public void getCameraSensors(@Nullable BlinkAccount account, CameraConfiguration camera) throws IOException {
        if (account == null || account.account == null) {
            throw new IllegalArgumentException("This Blink Account is not authenticated yet");
        }
        String uri = "/network/" + camera.networkId + "/camera/" + camera.cameraId + "/signals";
        String command = "";
        String json = request(account.account.tier, uri, HttpMethod.GET, account.auth.access_token, null, command);
        logger.trace("camera {} sensors: {}", camera.cameraId, json);
        return;
    }
}
