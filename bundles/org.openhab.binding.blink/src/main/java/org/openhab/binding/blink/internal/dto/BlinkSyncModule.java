/**
 * Copyright (c) 2010-2026 Contributors to the openHAB project
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
 * The {@link BlinkSyncModule} class is the DTO for networks returned by the homescreen api call.
 *
 */
public class BlinkSyncModule {

    public BlinkSyncModule(Long id) {
        this.id = id;
    }

    public Long id;
    public String name; // always "My Blink Sync Module"
    public String serial; // Property
    public String fw_version; // Property
    public String type; // Property. "sm1", "sm2", "kalahari", and "??"
    public String subtype; // Property. "none" for gen 1
    public String revision; // null for gen 1 sync modules
    public Integer wifi_strength; // Channel. 1-5, 5 is strong
    public Long network_id; // same id value as BlinkNetwork.id
    public boolean local_storage_enabled; // Property
    public boolean local_storage_compatible; // Property
    public String local_storage_status; // Property

    public Details details;

    // This content is retrieved from /network/<NETWORKID>/syncmodules
    // See NetworkService.getSyncModule()
    public class Details {
        public SyncModule syncmodule;

        public class SyncModule {
            public Long id;
            public String status; // "online", ???
            public String first_boot; // Property. date string like "2023-11-26T16:05:56+00:00"
        }
    }
}

/*-
 *  Notes about type:
 *     "sm1" is a gen-1 sync module. It is square, has a USB-A port but cannot store videos
 *     "sm2" is a gen-2 sync module. It is also square, and has a USB-A (USB 2.0) port for 1-256 GB thumb drives
 *     "kalahari" is a gen-3 sync module, a rounded rectangle with no USB port on it; cannot store videos
 *
 *
 * from homescreen:
   "sync_modules": [
    {
      "id": 1234,
      "created_at": "2019-06-22T15:05:21+00:00",
      "updated_at": "2026-01-15T19:49:59+00:00",
      "onboarded": true,
      "status": "online",
      "name": "My Blink Sync Module",
      "serial": "12341234",
      "fw_version": "4.5.36",
      "type": "sm1",
      "subtype": "none",
      "last_hb": "2026-01-15T23:59:16+00:00",
      "wifi_strength": 5,
      "network_id": 111222,
      "enable_temp_alerts": true,
      "local_storage_enabled": false,
      "local_storage_compatible": false,
      "local_storage_status": "unavailable",
      "revision": null
    },
    {
      "id": 12345,
      "created_at": "2023-10-16T22:09:46+00:00",
      "updated_at": "2026-01-15T19:51:06+00:00",
      "onboarded": true,
      "status": "online",
      "name": "My Blink Sync Module",
      "serial": "asdf1234",
      "fw_version": "16.0.33",
      "type": "sm2",
      "subtype": "billy",
      "last_hb": "2026-01-15T23:58:59+00:00",
      "wifi_strength": 5,
      "network_id": 111223,
      "enable_temp_alerts": true,
      "local_storage_enabled": false,
      "local_storage_compatible": true,
      "local_storage_status": "unavailable",
      "revision": "01"
    },
    {
      "id": 123456,
      "created_at": "2025-09-17T22:59:39+00:00",
      "updated_at": "2026-01-15T19:49:47+00:00",
      "onboarded": true,
      "status": "online",
      "name": "My Blink Sync Module",
      "serial": "asdf12341234",
      "fw_version": "21.1.13",
      "type": "kalahari",
      "subtype": "none",
      "last_hb": "2026-01-15T23:58:49+00:00",
      "wifi_strength": 5,
      "network_id": 111234,
      "enable_temp_alerts": true,
      "local_storage_enabled": false,
      "local_storage_compatible": false,
      "local_storage_status": "unavailable",
      "revision": "00"
    }


 *
 * from /network/<NETWORKID>/syncmodules:
 *
 * note: ip_address below appears to be my public IP assigned by my ISP, not an internal IP address.
 * mac_address is null in all of my data, although the sticker on the device provides it.
 * the "server" field looks like an AWS instance ID but no way to be sure.
 *
{
  "syncmodule": {
    "id": 12345,
    "created_at": "2023-10-16T22:09:46+00:00",
    "updated_at": "2026-02-03T18:10:23+00:00",
    "last_activity": "1970-01-01",
    "name": "My Blink Sync Module",
    "fw_version": "16.0.34",
    "mac_address": null,
    "ip_address": "10.11.12.13",
    "lfr_frequency": null,
    "serial": "asdf1234",
    "status": "online",
    "onboarded": true,
    "server": "i-xxxxx87f34",
    "last_hb": "2026-02-11T17:28:45+00:00",
    "os_version": "6.16.11",
    "last_wifi_alert": null,
    "wifi_alert_count": 0,
    "last_offline_alert": "2026-01-20T15:24:57+00:00",
    "offline_alert_count": 20,
    "table_update_sequence": 1711111117,
    "local_storage_enabled": false,
    "last_backup_started": null,
    "last_backup_completed": null,
    "last_backfill_completed": null,
    "backfill_in_progress": null,
    "ring_device_id": null,
    "first_boot": "2023-11-26T16:05:56+00:00",
    "feature_plan_id": null,
    "account_id": 1111,
    "network_id": 111223,
    "country_id": "US",
    "vo9_channel": 0,
    "wifi_strength": 5
  }


*/