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

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.blink.internal.dto.BlinkCamera;
import org.openhab.binding.blink.internal.dto.BlinkEvents;
import org.openhab.binding.blink.internal.dto.BlinkHomescreen;
import org.openhab.binding.blink.internal.handler.AccountHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This class stores the Blink Media Events for this account. It has a complete list of all events,
 * plus a time-sorted set of events for each camera, individually.
 *
 * Saved (homescreen) thumbnails are typically not in this list as they aren't from motion events.
 *
 * @author Robert T. Brown (-rb) - Initial contribution
 *
 */
public class MediaManager {
    private final Logger logger = LoggerFactory.getLogger(MediaManager.class);
    private final AccountHandler accountHandler;
    private final ScheduledExecutorService scheduler;
    private @NonNull ScheduledFuture<?> cacheCheckJob;
    private final long maxCacheSizeBytes = 100 * 1024 * 1024; // TODO: consider user configurable
    private final int cacheCheckRateMins = 10;
    private final Map<Long, BlinkEvents.Media> allEventsById = new ConcurrentHashMap<>();
    private final Map<Long, TreeSet<BlinkEvents.Media>> eventsByCameraId = new ConcurrentHashMap<>();
    private final Map<Long, String> cameraNamesByCameraId = new ConcurrentHashMap<>();
    private final Map<Long, byte[]> thumbnailImageByMediaId = new ConcurrentHashMap<>();
    private final Map<Long, byte[]> videoByMediaId = new ConcurrentHashMap<>();
    private final Map<String, byte[]> staticThumbnailByUri = new ConcurrentHashMap<>();
    private static EventComparator comparator = new EventComparator();

    public MediaManager(AccountHandler acctHandler, ScheduledExecutorService scheduler) {
        this.accountHandler = acctHandler;
        this.scheduler = scheduler;
        this.cacheCheckJob = scheduler.schedule(() -> cacheCheck(), cacheCheckRateMins, TimeUnit.MINUTES);
    }

    /**
     * A new motion event is retrieved from Blink, and passed in here. We track the event for
     * later display to the user.
     *
     * @param eventId the unique event id
     * @param event the event contents (metadata, not the image/video itself)
     */
    public void addEvent(Long eventId, BlinkEvents.Media event) {
        allEventsById.put(eventId, event);
        TreeSet<BlinkEvents.Media> thisCamerasEvents = eventsByCameraId.get(event.device_id);
        if (thisCamerasEvents == null) {
            thisCamerasEvents = new TreeSet<BlinkEvents.Media>(comparator);
            eventsByCameraId.put(event.device_id, thisCamerasEvents);
        }
        logger.trace("Storing event {}, along with {} other events for this camera", event, thisCamerasEvents.size());
        thisCamerasEvents.add(event);
        cacheCheck();
    }

    /**
     * a motion event was deleted from Blink (by the user, or expired by Blink servers). Forget it.
     *
     * @param eventId id of the deleted event.
     */
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

    /**
     * For a given media event Id, this method can retrieve the image itself, either from
     * our own internal cache, or from the Blink API (and subsequently cached here).
     *
     * @param mediaId the id of the event where the thumbnail path is stored
     * @return the raw bytes of the image itself
     * @throws IOException if we are unable to retrieve the image
     */
    public byte[] getImage(Long mediaId) throws IOException {
        BlinkEvents.Media mediaEvent = allEventsById.get(mediaId);
        if (mediaEvent == null) {
            logger.warn("Unknown Media motion event " + mediaId + ". Unable to return thumbnail image");
            throw new IOException("Unknown media event id " + mediaId);
        }
        byte[] thumbnailImage = thumbnailImageByMediaId.get(mediaId);
        if (thumbnailImage == null || thumbnailImage.length == 0) {
            // need to read it from the Blink Servers first, then cache here, and return it.
            thumbnailImage = accountHandler.getImage(mediaEvent.thumbnail);
            thumbnailImageByMediaId.put(mediaId, thumbnailImage);
        }
        return thumbnailImage;
    }

    /**
     * When the caller has a path, this method will return the image, either from cache, or freshly
     * fetched from the Blink APIs. Static "homepage" thumbnails don't correspond to motion events
     * so they typically have to be fetched.
     * 
     * @param imagePath the URI of the image
     * @return the raw bytes of the image
     * @throws IOException
     */
    public byte[] getImage(String imagePath) throws IOException {
        byte[] rawImage;
        Long imageId = parseMediaIdFromUri(imagePath);
        if (imageId != null) {
            // check the motion events cache for the thumbnail
            BlinkEvents.Media mediaEvent = allEventsById.get(imageId);
            if (mediaEvent != null) {
                return getImage(imageId);
            }
        }

        // not a motion event, let's check for static thumbnails
        rawImage = staticThumbnailByUri.get(imagePath);
        if (rawImage != null) {
            return rawImage;
        }
        // need to fetch the image from Blink and cache it here
        rawImage = accountHandler.getImage(imagePath);
        if (rawImage.length > 0) {
            staticThumbnailByUri.put(imagePath, rawImage);
        }
        return rawImage;
    }

    /**
     * motion events are typically video recordings, so we can fetch the video from Blink, using
     * the thumbnail path but changing the extension from jpg to mp4.
     *
     * @param mediaId the id of the motion event, from which the URL is derived
     * @return the raw bytes of the video file
     * @throws IOException
     */
    public byte[] getVideo(Long mediaId) throws IOException {
        BlinkEvents.Media mediaEvent = allEventsById.get(mediaId);
        if (mediaEvent == null) {
            throw new IOException("Unknown media event id " + mediaId);
        }
        byte[] videoImage = videoByMediaId.get(mediaId);
        if (videoImage == null || videoImage.length == 0) {
            // need to read it from the Blink Servers first, then cache here, and return it.
            videoImage = accountHandler.getImage(mediaEvent.thumbnail.replaceAll(".jpg", ".mp4"));
            videoByMediaId.put(mediaId, videoImage);
        }
        return videoImage;
    }

    private String getCameraName(long device_id) {
        String name = cameraNamesByCameraId.get(device_id);
        if (name == null) {
            name = "id " + device_id;
        }
        return name;
    }

    /**
     * Return a sorted map of camera name to id, for each camera with stored clips. This is used to define the swimlanes
     * in the MediaServlet.
     */
    public Map<String, Long> getCameraNamesToIds() {
        Map<String, Long> copyMap = new TreeMap<>();
        for (Long key : cameraNamesByCameraId.keySet()) {
            String name = cameraNamesByCameraId.get(key);
            if (name != null) {
                copyMap.put(name, key);
            }
        }
        return copyMap;
    }

    /**
     * As cameras are detected by the Blink API's, we track them here. Some of these cameras may not have
     * a openhab Thing yet (or ever), but they are still useful in displaying the timeline view of
     * historical video recordings, and allow the user to watch those videos even without a Thing.
     *
     * @param id
     * @param cameraName
     */
    public void registerCameraName(Long id, String cameraName) {
        this.cameraNamesByCameraId.put(id, cameraName);
    }

    /**
     * on each homescreen update, the list of cameras is updated here (useful if you rename your camera
     * in the blink app).
     *
     * @param homescreen
     */
    public void updateCameraList(BlinkHomescreen homescreen) {
        updateCameraList(homescreen.cameras);
        updateCameraList(homescreen.doorbells);
        updateCameraList(homescreen.owls);
    }

    private void updateCameraList(List<BlinkCamera> cameraList) {
        for (BlinkCamera cam : cameraList) {
            registerCameraName(cam.id, cam.name);
        }
    }

    /**
     * Used internally, and by the MediaServlet to show the user how much memory is being used
     * by the media cache. The user can clear the cache on demand, but there's also a background
     * job to periodically uncache some things to get back under the max configured cache size.
     * 
     * @return a DTO of the cache statistics
     */
    public CacheStats getCacheStats() {
        CacheStats stats = new CacheStats();
        stats.image_count = thumbnailImageByMediaId.size() + staticThumbnailByUri.size();
        long numBytes = 0;
        for (byte[] image : thumbnailImageByMediaId.values()) {
            numBytes += image.length;
        }
        for (byte[] image : staticThumbnailByUri.values()) {
            numBytes += image.length;
        }
        stats.image_size_bytes = numBytes;

        stats.video_count = videoByMediaId.size();
        numBytes = 0;
        for (byte[] video : videoByMediaId.values()) {
            numBytes += video.length;
        }
        stats.video_size_bytes = numBytes;
        stats.max_cache_bytes = maxCacheSizeBytes;
        return stats;
    }

    /**
     * Determine the media id based on the blink URI. There are two observed URI formats:
     * 1. the media event update from a motion detection event, or "latest motion thumbnail", looks like:
     * /api/v3/media/accounts/6196/networks/6635/catalina/1201330/pir/16324811771.jpg?ext=
     * /api/v3/media/accounts/6196/networks/580601/lotus/256160/pir/16324932818.jpg?ext=
     * /api/v3/media/accounts/6196/networks/11166/camera/1384685/pir/16251052696.jpg?ext=
     *
     * 2. the saved "homescreen" thumbnail for a device has a URI such as one of these:
     * /api/v3/media/accounts/6196/networks/6635/catalina/1201330/thumbnail/thumbnail.jpg?ts=1717629084&ext=
     * /api/v3/media/accounts/6196/networks/580601/lotus/256160/thumbnail/thumbnail.jpg?ts=1734022969&ext=
     * /api/v3/media/accounts/6196/networks/11166/xt/1384685/thumbnail/thumbnail.jpg?ts=1725809426&ext=
     *
     * @param uri the thumbnail uri, expected to be like an above example
     * @return the media id number, e.g. 1717629084
     */
    @Nullable
    private Long parseMediaIdFromUri(String uri) {
        Pattern patt = Pattern.compile(".*/(\\d+)\\.(jpg|jpeg|mp4)");
        Matcher match = patt.matcher(uri);
        try {
            if (match.find()) {
                // the match ensures that it's all numeric digits, so the parseLong() should work.
                return Long.parseLong(match.group(1));
            } else {
                // fall back to the other possible format thumbnail.jpg?ts=###&ext=...
                patt = Pattern.compile("\\?.*ts=(\\d+)");
                match = patt.matcher(uri);
                if (match.find()) {
                    // the match ensures that it's all numeric digits, so the parseLong() should work.
                    return Long.parseLong(match.group(1));
                }
                return null;
            }
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    /**
     * This method clears the cache on demand.
     */
    public void clearCache() {
        CacheStats cache = getCacheStats();
        long totalSize = cache.image_size_bytes + cache.video_size_bytes;
        String sizeMbStr = String.format("%.1f", totalSize / 1024.0 / 1024);
        logger.info("Cache has been cleared. Was " + sizeMbStr + " MB in size.");
        thumbnailImageByMediaId.clear();
        videoByMediaId.clear();
        staticThumbnailByUri.clear();
    }

    /**
     * Determine if the cache is above the configured maximum, and if so, reduce it some.
     */
    private void cacheCheck() {
        CacheStats cache = getCacheStats();
        long currentCacheSize = cache.image_size_bytes + cache.video_size_bytes;
        if (currentCacheSize > maxCacheSizeBytes) {
            logger.info(String.format("Cache stats: %.1f MB, out of %.1f MB allowed. Decaching now...",
                    currentCacheSize / 1024.0 / 1024, maxCacheSizeBytes / 1024.0 / 1024));
            long targetBytes = currentCacheSize - (maxCacheSizeBytes * 3 / 5); // aim for 60% full
            long progressBytes = 0;
            Iterator<Long> thumbIt = thumbnailImageByMediaId.keySet().iterator();
            Iterator<Long> videoIt = videoByMediaId.keySet().iterator();
            int countThumbs = 0;
            int countVideos = 0;
            while (progressBytes < targetBytes && (thumbIt.hasNext() || videoIt.hasNext())) {
                if (thumbIt.hasNext()) {
                    byte[] target = thumbnailImageByMediaId.get(thumbIt.next());
                    if (target != null) {
                        progressBytes += target.length;
                        thumbIt.remove();
                        countThumbs++;
                    }
                }
                if (videoIt.hasNext()) {
                    byte[] target = videoByMediaId.get(videoIt.next());
                    if (target != null) {
                        progressBytes += target.length;
                        videoIt.remove();
                        countVideos++;
                    }
                }
            }
            logger.debug(String.format("cacheCheck: cleared cache of %.1f MB of data, from %d images and %d videos,",
                    progressBytes / 1024.0 / 1024, countThumbs, countVideos));
            cache = getCacheStats();
            logger.info(String.format("Cache stats: %.1f MB, out of %.1f MB allowed.", currentCacheSize / 1024.0 / 1024,
                    maxCacheSizeBytes / 1024.0 / 1024));
        }
        cacheCheckJob = scheduler.schedule(() -> cacheCheck(), cacheCheckRateMins, TimeUnit.MINUTES);
    }

    /**
     * empty the cache and cease background threads. Typically used on my death bed.
     */
    public void cleanup() {
        cacheCheckJob.cancel(true);
        clearCache();
    }

    public static class EventComparator implements Comparator<BlinkEvents.Media> {
        @Override
        public int compare(BlinkEvents.Media a, BlinkEvents.Media b) {
            return OffsetDateTime.timeLineOrder().compare(a.created_at, b.created_at);
        }
    }

    public class CacheStats {
        Integer image_count;
        Long image_size_bytes;
        Integer video_count;
        Long video_size_bytes;
        Long max_cache_bytes;
    }
}
