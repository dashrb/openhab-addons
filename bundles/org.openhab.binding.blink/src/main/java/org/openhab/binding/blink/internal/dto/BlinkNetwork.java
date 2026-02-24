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
 * The {@link BlinkNetwork} class is the DTO for networks returned by the homescreen api call.
 *
 * @author Matthias Oesterheld - Initial contribution
 */
public class BlinkNetwork {

    public BlinkNetwork(Long id) {
        this.id = id;
    }

    public Long id; // Thing UID
    public String name; // Thing name
    public boolean armed; // Channel
    public Details details;

    // This content is retrieved from /network/<NETWORKID>
    // See NetworkService.getDetais()
    public class Details {
        public Network network;

        public class Network {
            public String video_destination; // Property
            public Long storage_used; // Property, only if syncModule.local_storage_compatible
            public Long storage_total; // Property, only if syncModule.local_storage_compatible
            public boolean busy; // looks like if true, we need to delay / retry certain commands
            public String status; // "armed" ?
        }
    }
}

/*-
*
* Sample docs: the Details object looks like this:
{
  "network": {
    "id": 1234121,
    "created_at": "2023-10-16T22:09:41+00:00",
    "updated_at": "2025-09-16T03:00:11+00:00",
    "deleted_at": null,
    "name": "North",
    "network_key": "UCxxxxxxxxx6i",
    "description": "",
    "network_origin": "normal",
    "locale": "",
    "time_zone": "America/New_York",
    "dst": true,
    "ping_interval": 60,
    "encryption_key": null,
    "armed": true,
    "autoarm_geo_enable": false,
    "autoarm_time_enable": false,
    "lv_mode": "relay",
    "lfr_channel": 0,
    "video_destination": "server",
    "storage_used": 0,
    "storage_total": 0,
    "video_count": 0,
    "video_history_count": 4000,
    "sm_backup_enabled": false,
    "arm_string": "Armed",
    "busy": false,
    "camera_error": false,
    "sync_module_error": false,
    "feature_plan_id": null,
    "location_id": null,
    "account_id": 12345678,
    "status": "armed"
  }
}
*/
