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
package org.openhab.binding.blink.internal.dto;

/**
 * The {@link BlinkCamera} class is the DTO for cameras returned in the homescreen api call.
 *
 * @author Matthias Oesterheld - Initial contribution
 */
public class BlinkCamera {

    public BlinkCamera(Long networkId, Long cameraId) {
        this.network_id = networkId;
        this.id = cameraId;
    }

    public Long id; // in UID
    public Long network_id; // Property.
    public String name; // Property. (probably matches thing label)
    public String created_at; // Property. parseable as date/time
    public String updated_at; // Channel-Advanced. could be last heard from. my dead battery one has a 2-months-ago date
    public String serial; // Property.
    public String fw_version; // Property. firmware versions. Old cameras are not getting updates anymore
    public boolean usage_rate; // Channel-Advanced. true means it is experiencing high usage rate according to Blink
    public boolean enabled; // Channel. (Motion detection)
    public String type; // Property. white (indoor), xt, xt2, catalina (gen3), sedona (gen4), owl (mini), lotus
                        // (doorbell)
    public String thumbnail; // Property. url to current homescreen thumbnail
    public String status; // Set as Status? I have seen values: 'done' or 'offline' (for dead batteries)
    public String battery; // Channel(Low Battery). this is a string 'ok' or ??
    public String revision; // Property. older cameras are null, but my gen3 and gen4 say "01"
    public String color; // Property. most are "black" but my old gen1 indoor cameras and mini say "white"

    public Signals signals; // this entire block is valid for Cameras and doorbells, but not owls/minis.
    public Details details; // this entire block is only valid for Cameras, not doorbells, and not owls/minis.

    public class Signals {
        public int wifi; // Channel-Advanced. A 1-5? range, 5 appears to be very strong, 2 is weak but still works
        public int lfr; // Channel-Advanced. 1-5 strength to reach the sync module
        public double temp; // Channel. In farenheit for me, probably converted to C in the app, if so configured
    }

    public class Details {
        public HardwareDetails camera[]; // it's an array but the API call is for exactly one camera, so use [0].
        public SignalsDetails signals;
    }

    public class HardwareDetails {
        public String mac_address; // Property.
        public int battery_voltage; // Channel. in hundredths of volts (centivolts), e.g. 171 means 1.71v
        public String battery_check_time; // Property. parseable as date/time
        public String first_boot; // Property. parseable as date/time
        public ConnectionDetails last_connect;
    }

    public class ConnectionDetails {
        public String ip_address; // Property
        public boolean ac_power; // Property
        public long socket_failure_count; // Property
    }

    public class SignalsDetails {
        // Note that wifi and lfr are also in "signals" at the top level, which doorbells DO report.
        public int wifi; // Channel-Advanced. A 1-5 range, 5 appears to be very strong, 2 is weak but still works
        public int wifi_rssi; // Channel-Advanced. an RSSI strength is always negative, more negative is weaker
        public int lfr; // Channel-Advanced. 1-5 strength to reach the sync module
        public int lfr_rssi; // Channel-Advanced. an RSSI strength is always negative, more negative is weaker
    }
}
