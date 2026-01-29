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
 *
 * @author Robert T. Brown (-rb) - added time-sorted storage of historical media events for each camera
 */

package org.openhab.binding.blink.internal;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

import org.openhab.binding.blink.internal.dto.BlinkEvents;
import org.openhab.core.thing.Thing;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class stores the Blink Media Events for this account. It has a complete list of all events,
 * plus a time-sorted set of events for each camera, individually.
 *
 * @author Robert T. Brown (-rb) - Initial contribution
 *
 */
public class MediaManager {
    private final Logger logger = LoggerFactory.getLogger(MediaManager.class);
    private final Map<Long, BlinkEvents.Media> allEventsById = new ConcurrentHashMap<>();
    private final Map<Long, TreeSet<BlinkEvents.Media>> eventsByCameraId = new ConcurrentHashMap<>();
    private final Map<Long, Thing> cameraThingsByCameraId = new HashMap<>();

    private static EventComparator comparator = new EventComparator();

    public MediaManager() {
    }

    public void addEvent(Long eventId, BlinkEvents.Media event) {
        allEventsById.put(eventId, event);
        TreeSet<BlinkEvents.Media> thisCamerasEvents = eventsByCameraId.get(event.device_id);
        if (thisCamerasEvents == null) {
            thisCamerasEvents = new TreeSet<BlinkEvents.Media>(comparator);
            eventsByCameraId.put(event.device_id, thisCamerasEvents);
        }
        logger.debug("Storing event {}, along with {} other events for this camera", event, thisCamerasEvents.size());
        thisCamerasEvents.add(event);
    }

    public void removeEvent(Long eventId) {
        // try to remove the event from the master list
        BlinkEvents.Media victim = allEventsById.remove(eventId);
        if (victim == null) {
            // we weren't storing this event, return now.
            return;
        }
        // subsequently remove the event from the camera's list
        TreeSet<BlinkEvents.Media> thisCamerasEvents = eventsByCameraId.get(victim.device_id);
        if (thisCamerasEvents != null) {
            thisCamerasEvents.remove(victim);
        }
    }

    /**
     * Returns a set of the latest motion event per camera (if it has one).
     * Thus, the number of items returned is <= the number of cameras in the account.
     *
     * @return a set of the latest motion event per camera
     */
    public Set<BlinkEvents.Media> getLatestMotionEvents() {
        logger.debug("Returning latest motion event for {} cameras", eventsByCameraId.size());
        Set<BlinkEvents.Media> result = new HashSet<>();
        for (Long key : eventsByCameraId.keySet()) {
            TreeSet<BlinkEvents.Media> eventsForThisCam = eventsByCameraId.get(key);
            if (eventsForThisCam != null && eventsForThisCam.size() > 0) {
                BlinkEvents.Media latestEntry = eventsForThisCam.last();
                result.add(latestEntry);
                logger.debug("Camera {} has {} recordings, most recent is {}", getCameraName(key),
                        eventsForThisCam.size(), latestEntry);
            } else {
                logger.debug("Camera {} has 0 recordings so far", getCameraName(key));
            }
        }
        return result;
    }

    public Collection<BlinkEvents.Media> getAllMotionEvents() {
        return allEventsById.values();
    }

    private String getCameraName(long device_id) {
        String name = "id " + device_id;
        Thing cam = cameraThingsByCameraId.get(device_id);
        if (cam != null) {
            String label = cam.getLabel();
            if (label != null && label.length() > 0) {
                name = cam.getLabel();
            }
        }
        return name;
    }

    /**
     * Return a sorted map of camera name to id, for each camera with stored clips. This is used to define the swimlanes
     * in the MediaServlet.
     */
    public Map<String, Long> getCamerasToIds() {
        Map<String, Long> copyMap = new TreeMap<>();
        for (Long key : cameraThingsByCameraId.keySet()) {
            Thing cam = cameraThingsByCameraId.get(key);
            if (cam != null) {
                copyMap.put(cam.getLabel(), key);
            }
        }
        return copyMap;
    }

    public void registerCameraThing(Long id, Thing camera) {
        this.cameraThingsByCameraId.put(id, camera);
    }

    public static class EventComparator implements Comparator<BlinkEvents.Media> {

        @Override
        public int compare(BlinkEvents.Media a, BlinkEvents.Media b) {
            return OffsetDateTime.timeLineOrder().compare(a.created_at, b.created_at);
        }
    }
}
