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
package org.openhab.binding.blink.internal.servlet;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.blink.internal.MediaManager;
import org.openhab.binding.blink.internal.dto.BlinkEvents;
import org.openhab.binding.blink.internal.handler.AccountHandler;
import org.openhab.binding.blink.internal.servlet.MediaServlet.Clips.Lane;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * The {@link MediaServlet} class provides the servlet for outputting a camera's current Media.
 *
 * REST API: starts w/URI: /blink/account/1234abcd, where "1234abcd" is your Blink Account Thing UID
 * == GET
 * ---- /media -> "homepage" for browser to hit, returns html w/ javascript
 * ---- /media/clips -> returns JSON with list of all known video clip recordings
 * ---- /media/cache -> returns JSON with current cache usage information
 * ---- /media/image/1234 -> returns image/jpeg content for image w/media event ID 1234
 * ---- /media/video/1234 -> returns video/mp4 content for video w/media event ID 1234
 * == POST
 * ---- There are no POST endpoints
 * == PUT
 * ---- /media/cache/clear -> Asks the server to clear its image/video cache. No content returned.
 *
 * @author Matthias Oesterheld - Initial contribution
 */
public class MediaServlet extends HttpServlet {

    public static final long serialVersionUID = 666L;

    private final Logger logger = LoggerFactory.getLogger(MediaServlet.class);
    private final HttpService httpService;
    private final AccountHandler accountHandler;
    private final String servletUrl;
    private final String accountId;

    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public MediaServlet(HttpService httpService, AccountHandler accountHandler) {
        this.httpService = httpService;
        this.accountHandler = accountHandler;

        try {
            accountId = URLEncoder.encode(this.accountHandler.getThing().getUID().getId(), StandardCharsets.UTF_8);
            servletUrl = "/blink/account/" + accountId + "/media";
            logger.debug("Registered media servlet at {}", servletUrl);

            Hashtable<Object, Object> initParams = new Hashtable<>();
            initParams.put("servlet-name", servletUrl);

            httpService.registerServlet(servletUrl, this, initParams, httpService.createDefaultHttpContext());
        } catch (NamespaceException | ServletException e) {
            throw new IllegalStateException(e.getMessage());
        }
    }

    public void dispose() {
        httpService.unregister(servletUrl);
    }

    @Override
    protected void doPut(@Nullable HttpServletRequest request, @Nullable HttpServletResponse response)
            throws IOException {
        if (response == null) {
            logger.warn("Ignoring received request without response.");
            return;
        }
        if (request == null) {
            logger.warn("Ignoring illegal request.");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String pathInfo = request.getPathInfo();
        if (pathInfo == null) {
            logger.warn("Ignoring illegal request.");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        } else {
            if (pathInfo.equals("/cache/clear")) {
                MediaManager mediaManager = accountHandler.getMediaManager();
                mediaManager.clearCache();
            }
        }
        response.setStatus(HttpServletResponse.SC_OK);
    }

    @Override
    protected void doPost(@Nullable HttpServletRequest request, @Nullable HttpServletResponse response)
            throws IOException {
        if (response == null) {
            logger.warn("Ignoring received request without response.");
            return;
        }
        if (request == null) {
            logger.warn("Ignoring illegal request.");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        doGet(request, response);
    }

    @Override
    protected void doGet(@Nullable HttpServletRequest request, @Nullable HttpServletResponse response)
            throws IOException {
        if (response == null) {
            logger.warn("Ignoring received request without response.");
            return;
        }
        if (request == null) {
            logger.warn("Ignoring illegal request.");
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }
        String pathInfo = request.getPathInfo();
        if (pathInfo == null) {
            sendBasePage(response);
        } else {
            if (pathInfo.equals("/clips")) {
                sendClipsMetadata(response);
            } else if (pathInfo.startsWith("/image/")) {
                logger.trace("Client requests image thumbnail: " + pathInfo);
                // strip off the "/image/" part to get the mediaId
                String mediaId = pathInfo.substring("/image/".length());
                if (mediaId.isBlank()) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Expected a MEDIA_ID value after /image/");
                    return;
                }
                sendImage(request, response, mediaId);
            } else if (pathInfo.startsWith("/video/")) {
                logger.trace("Client requests full video: " + pathInfo);
                // strip off the "/video/" part to get the mediaId
                String mediaId = pathInfo.substring("/video/".length());
                if (mediaId.isBlank()) {
                    response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Expected a MEDIA_ID value after /image/");
                    return;
                }
                sendVideo(request, response, mediaId);
            } else if (pathInfo.startsWith("/cache")) {
                logger.trace("Client requests cache update: " + pathInfo);
                sendCacheStats(request, response);
            } else {
                PrintWriter w = response.getWriter();
                w.println("Error: unrecognized path " + request.getPathInfo());
            }
        }
    }

    private void sendImage(HttpServletRequest request, HttpServletResponse response, String mediaId)
            throws IOException {
        byte[] image = getImage(Long.valueOf(mediaId));
        OutputStream out = response.getOutputStream();
        response.setContentLength(image.length);
        response.setContentType("image/jpeg");
        out.write(image);
    }

    private byte[] getImage(Long mediaId) throws IOException {
        MediaManager mediaManager = accountHandler.getMediaManager();
        return mediaManager.getImage(mediaId);
    }

    private void sendVideo(HttpServletRequest request, HttpServletResponse response, String mediaId)
            throws IOException {
        byte[] image = getVideo(Long.valueOf(mediaId));
        OutputStream out = response.getOutputStream();
        response.setContentLength(image.length);
        response.setContentType("video/mp4");
        out.write(image);
    }

    private byte[] getVideo(Long mediaId) throws IOException {
        MediaManager mediaManager = accountHandler.getMediaManager();
        return mediaManager.getVideo(mediaId);
    }

    private void sendCacheStats(HttpServletRequest request, HttpServletResponse response) throws IOException {
        MediaManager mediaManager = accountHandler.getMediaManager();
        MediaManager.CacheStats stats = mediaManager.getCacheStats();
        response.setContentType("application/json");
        String json = gson.toJson(stats);
        PrintWriter out = response.getWriter();
        out.println(json);
        out.flush();
        logger.debug("Sent cache stats to browser " + request.getRemoteAddr() + ": " + json);
    }

    /*-
     * Sends a JSON string suitable for the swimlanes logic to display the content. Format:
     *
     * {
     *    lanes: [
     *        { id: 0, lane: "LaneName" },   // id is a unique lane id number
     *        ...
     *        ]
     *    items: [
     *        { id: 0, lane: laneId, start: "date/time str", end: "date/time str",
     *          cssClass: "css class name", desc: "description here" },
     *        ...
     *        ]
     * }
     *
     * In items:
     *     id = a unique item number
     *     laneId = a lane id, must match an id value from the lanes object
     *     start/end = date/time strings representing the duration of the recording
     *     cssClass = a CSS class name for the drawn rectangle, e.g. "new", "viewed", "deleted"
     *     desc = a description of the event (not clear what I would use this for)
     *
     * @param response
     * @throws IOException
     */
    private void sendClipsMetadata(HttpServletResponse response) throws IOException {
        PrintWriter w = response.getWriter();
        MediaManager mediaManager = accountHandler.getMediaManager();
        response.setContentType("application/json");
        Map<String, Long> camNameToCamId = mediaManager.getCameraNamesToIds();
        Map<Long, Clips.Lane> camIdToLane = new HashMap<>();
        Clips clips = new Clips();
        List<Lane> lanes = new ArrayList<>();
        clips.lanes = lanes;
        int laneNumber = 0;
        for (String name : camNameToCamId.keySet()) {
            Long camId = camNameToCamId.get(name);
            // of course it's not null, these are iterated keys in the for loop.
            if (camId != null) {
                Lane lane = clips.new Lane();
                lane.id = laneNumber;
                lane.label = name;
                lanes.add(lane);
                camIdToLane.put(camId, lane);
                laneNumber++;
            }
        }
        int unknownLaneNumber = laneNumber;
        Lane unknownLane = null; // we initialize this below, only if needed

        List<Clips.Item> items = new ArrayList<>();
        clips.items = items;
        for (BlinkEvents.Media rec : mediaManager.getAllMotionEvents()) {
            Clips.Item item = clips.new Item();
            item.id = rec.id;
            Clips.Lane lane = camIdToLane.get(rec.device_id);
            if (lane == null) {
                if (unknownLane == null) {
                    // our first recording from an unknown camera (deleted? re-added? not sure)
                    unknownLane = clips.new Lane();
                    unknownLane.id = unknownLaneNumber;
                    unknownLane.label = "Unknown";
                    lanes.add(unknownLane);
                }
                lane = unknownLane;
            }
            item.lane = lane.id;
            lane.numClips++;
            item.start = rec.created_at.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().toString();
            // TODO: figure out recording duration
            item.end = rec.created_at.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().plusSeconds(30)
                    .toString();
            item.cssClass = (!rec.watched) ? "new" : "viewed";
            if (rec.deleted) {
                item.cssClass = item.cssClass + " deleted"; // add this as a second CSS class
            }
            items.add(item);
        }
        String json = gson.toJson(clips);
        w.println(json);
        w.flush();
        logger.trace("Sent json: " + json);
    }

    private void sendBasePage(HttpServletResponse response) throws IOException {
        PrintWriter w = response.getWriter();
        response.addHeader("content-type", "text/html");
        // I thought about making this a JSP page because of these substitutions but that's a lot more complexity.
        String fontFamily = "Tahoma";
        String fontSize = "16px";
        w.println("<html>\n<head>\n<title>Blink Media for account " + accountId + "</title>");
        w.println("""
                <script type="text/javascript" src="https://d3js.org/d3.v7.js"></script>
                <style>
                body {
                """);
        w.println("    font-family: " + fontFamily + ", Verdana, Arial, sans-serif;");
        w.println("""
                }
                .chart {
                    shape-rendering: crispEdges;
                    background: #f9f9f9;
                """);
        w.println("    font-size: " + fontSize);
        w.println("""
                }

                h1 {
                    margin-left: 120px;
                }

                .mini text {
                    font: 12px Tahoma Verdana Arial sans-serif;
                }

                .axisTop text {
                    text-anchor: start;
                }

                .axis line, .axis path {
                    stroke: black;
                }

                .miniItem {
                    stroke-width: 12;
                }

                .mainCanvas {
                    opacity: 0;
                    pointer-events: all;
                }

                .new {
                    stroke: green;
                    fill: green;
                }
                .viewed {
                    stroke: blue;
                    fill: blue;
                }
                .deleted {
                    stroke: red;
                    fill: red;
                }
                .highlighted {
                    animation: highlighted 2s linear infinite;
                    -webkit-animation: highlighted 2s linear infinite;
                    transform-origin: center;
                }
                @keyframes highlighted {
                    0% {
                        stroke-width: 1px;
                        opacity: 1;
                       }
                    100% {
                        stroke-width: 15px;
                        opacity: 0.5;
                       }
                }
                @-webkit-keyframes highlighted {
                    0% {
                        stroke-width: 1px;
                        opacity: 1;
                       }
                    100% {
                        stroke-width: 15px;
                        opacity: 0.5;
                       }
                }
                .brush .extent {
                    stroke: gray;
                    fill: blue;
                    fill-opacity: .165;
                }
                .funnel {
                    stroke: red;
                    opacity: 0.5;
                }
                #laneCountHeader {
                    text-decoration: underline;
                    stroke: black;
                    font: 14px Tahoma;
                }
                #laneCountTotal {
                    text-anchor: end;
                    stroke: black;
                    font: 14px Tahoma;
                }
                .laneCount {
                    text-anchor: end;
                    stroke: black;
                    font: 12px Tahoma;
                }
                .layout {
                    display: grid;
                    grid-template-columns: 1000px 640px;
                    grid-template-rows: 380px auto;
                }
                .summary_grid {
                    display: grid;
                    grid-template-columns: max-content, auto;
                    grid-template-rows: auto;
                    gap: 5px;
                }
                .summary_header {
                    font: 24px Tahoma;
                    font-weight: bold;
                    text-anchor: center;
                }
                .summary_label {
                    text-anchor: right;
                    color: black;
                    font: 16px Tahoma;
                    font-weight: bold;
                }
                .summary_value {
                    color: black;
                    font: 16px Tahoma;
                }

                .video {
                    position: relative;
                    text-align: center;
                }
                .image_loading {
                    position: absolute;
                    left: 0px;
                    top: 50%;
                    color: gray;
                    z-index: -1;
                    margin: auto;
                    width: 100%;
                }
                .button_div {
                    left: 25%;
                    width: 50%;
                }
                .button {
                    background-color: #04aa6d;
                    color: white;
                    font-family: Tahoma, sans-serif;
                    font-size: 16px;
                    padding: 6px 25px;
                    margin: auto;
                    border-radius: 5px;
                    border: none;
                    display: block;
                    outline: 0;
                    vertical-align: middle;
                    text-align: center;
                    cursor: pointer;
                }

                </style>
                </head>
                <body>
                <h1>Blink Recorded Video Clips</h1>
                <div class="layout">
                  <div class="swimlanes" style="grid-row: 1 / span 2; grid-column: 1;"></div>
                  <div class="video" style="grid-row: 1; grid-column: 2;">
                      <div class='image_loading'>Click or Hover on a recording to view</div>
                  </div>
                  <div class="summary" style="grid-row: 2; grid-column: 2;">
                    <div class="summary_grid">
                      <div class="button_div" style="grid-column: 2;">
                        <button class='button' id='video_btn'>Watch Video</button>
                      </div>
                      <div style="grid-column: 1;">
                        <span class="summary_label" id="camera_label">Camera:</span>
                      </div>
                      <div style="grid-column: 2;">
                        <span class="summary_value" id="camera_value"></span>
                      </div>
                      <div style="grid-column: 1;">
                        <span class="summary_label" id="start_label">Recorded:</span>
                      </div>
                      <div style="grid-column: 2;">
                        <span class="summary_value" id="start_value"></span>
                      </div>
                      <div style="grid-column: 1;">
                        <span class="summary_label" id="duration_label">Duration:</span>
                      </div>
                      <div style="grid-column: 2;">
                        <span class="summary_value" id="duration_value">0:30 (est)</span>
                      </div>
                      <div style="grid-column: 1 / span 2; padding: 10px;"></div>
                      <div style="grid-column: 2; padding: 10px;">
                        <span class="summary_header">Cache Information</span>
                      </div>
                      <div style="grid-column: 1;">
                        <span class="summary_label">Images:</span>
                      </div>
                      <div style="grid-column: 2;">
                        <span class="summary_value" id="cache_images">0 (0 MB)</span>
                      </div>
                      <div style="grid-column: 1;">
                        <span class="summary_label">Videos:</span>
                      </div>
                      <div style="grid-column: 2;">
                        <span class="summary_value" id="cache_videos">0 (0 MB)</span>
                      </div>
                      <div style="grid-column: 1;">
                        <span class="summary_label">Cache Usage:</span>
                      </div>
                      <div style="grid-column: 2;">
                        <span class="summary_value" id="cache_usage"></span>
                      </div>
                      <div class="button_div" style="grid-column: 2;">
                        <button class='button' id='clear_cache_btn'>Clear Cache</button>
                      </div>
                    </div>
                  </div>
                </div>
                <script type="text/javascript">
                function reviveDate(key, value) {
                    if (key==="start" || key==="end") {
                        return typeof value === 'string' ? new Date(value) : value;
                    };
                    return value;
                }
                """);
        w.println("var accountId = '" + accountId + "';");
        w.println("var fontSize = '" + fontSize + "';");
        w.println("var fontFamily = '" + fontFamily + "';");
        w.println("""
                var xmlhttp = new XMLHttpRequest();
                xmlhttp.open("GET", "/blink/account/" + accountId + "/media/clips", false);
                xmlhttp.send();
                setInterval(updateCacheData, 60000);
                //console.log('recorded clips:', xmlhttp.responseText);
                var data = JSON.parse(xmlhttp.responseText, reviveDate);
                var lanes = data.lanes;
                var items = data.items;
                var now = new Date();
                var camIdStrToDetails = new Map();
                for (var ii=0; ii < items.length; ii++) {
                    camIdStrToDetails.set(''+items[ii].id, items[ii]);
                }
                var laneIdIntToDetails = new Map();
                for (var ii=0; ii < lanes.length; ii++) {
                    laneIdIntToDetails.set(lanes[ii].id, lanes[ii]);
                }


                const leftAxisTextWidth = calculateMaxWidth(lanes);
                const margin = {top: 20, right: 80, bottom: 15, left: leftAxisTextWidth+10};
                const width = 960 - margin.left - margin.right;
                const miniHeight = lanes.length * 12 + 50;
                const mainHeight = lanes.length * 20 + 50;
                const height = mainHeight + miniHeight + 50;

                var xMini = d3.scaleTime()
                        .domain([d3.min(items, d => d.start), d3.max(items, d => d.end)])
                        .range([1, width-2]);

                var xMain = d3.scaleTime().range([1, width-2]);

                var ext = d3.extent(lanes, function(d) { return d.id; });
                var yMain = d3.scaleLinear().domain([ext[0], ext[1] + 1]).range([0, mainHeight]);
                var yMini = d3.scaleLinear().domain([ext[0], ext[1] + 1]).range([0, miniHeight]);

                var chart = d3.select('.swimlanes')
                    .append('svg:svg')
                    .attr('width', width + margin.right + margin.left)
                    .attr('height', height + margin.top + margin.bottom)
                    .attr('class', 'chart');

                chart.append('defs').append('clipPath')
                    .attr('id', 'clip')
                    .append('rect')
                        .attr('width', width)
                        .attr('height', mainHeight);

                chart.append('g')
                    .attr('class', 'funnel left')
                    .attr('transform', 'translate(' + margin.left + ',' + margin.top + ')')
                    .append('line');

                chart.append('g')
                    .attr('class', 'funnel right')
                    .attr('transform', 'translate(' + margin.left + ',' + margin.top + ')')
                    .append('line');

                var main = chart.append('g')
                    .attr('transform', 'translate(' + margin.left + ',' + margin.top + ')')
                    .attr('width', width)
                    .attr('height', mainHeight)
                    .attr('class', 'main');

                var zoom = d3.zoom().on("zoom", zoomed);
                main.append('rect')
                    .attr('x', 0)
                    .attr('y', 0)
                    .attr('width', width)
                    .attr('height', mainHeight)
                    .attr('class', 'mainCanvas')
                    .on('pointerdown', event => mouseClicked(event))
                    .on('wheel', event => zoomed(event))
                    .call(zoom)
                    ;

                var mini = chart.append('g')
                    .attr('transform', 'translate(' + margin.left + ',' + (mainHeight + 60) + ')')
                    .attr('width', width)
                    .attr('height', miniHeight)
                    .attr('class', 'mini');

                main.append('g').selectAll('.laneLines')
                    .data(lanes)
                    .enter().append('line')
                    .attr('x1', 0)
                    .attr('y1', function(d) { return d3.format(".1f")((yMain(d.id)) + 0.5); })
                    .attr('x2', width)
                    .attr('y2', function(d) { return d3.format(".1f")((yMain(d.id)) + 0.5); })
                    .attr('stroke', function(d) { return d.label === '' ? 'white' : 'lightgray' });

                main.append('g').selectAll('.laneText')
                    .data(lanes)
                    .enter().append('text')
                    .text(function(d) { return d.label; })
                    .attr('x', -10)
                    .attr('y', function(d) { return yMain(d.id + .5); })
                    .attr('dy', '0.5ex')
                    .attr('text-anchor', 'end')
                    .attr('class', 'laneText');

                main.append('g').selectAll('.laneCount')
                    .data(lanes)
                    .enter().append('text')
                    .attr('x', width-margin.right)
                    .attr('y', d => yMain(d.id + .5) )
                    .attr('class', d => 'laneCount lane_' + d.id);

                mini.append('g').selectAll('.laneLines')
                    .data(lanes)
                    .enter().append('line')
                    .attr('x1', 0)
                    .attr('y1', function(d) { return d3.format(".1f")((yMini(d.id)) + 0.5); })
                    .attr('x2', width)
                    .attr('y2', function(d) { return d3.format(".1f")((yMini(d.id)) + 0.5); })
                    .attr('stroke', function(d) { return d.label === '' ? 'white' : 'lightgray' });

                mini.append('g').selectAll('.laneText')
                    .data(lanes)
                    .enter().append('text')
                    .text(function(d) { return d.label; })
                    .attr('x', -10)
                    .attr('y', function(d) { return yMini(d.id + .5); })
                    .attr('dy', '0.5ex')
                    .attr('text-anchor', 'end')
                    .attr('class', 'laneText');

                mini.append('g').selectAll('.laneCount')
                    .data(lanes)
                    .enter().append('text')
                    .attr('x', width + 35)
                    .attr('y', d => yMini(d.id + .5) + 4 )
                    .attr('class', d => 'laneCount lane_' + d.id)
                    .text( d => d.numClips );

                var miniLaneCount = mini.append('g');
                miniLaneCount.append('text')
                    .attr('x', width + 5)
                    .attr('y', -5)
                    .attr('id', 'laneCountHeader')
                    .text('Count');
                miniLaneCount.append('line')
                    .attr('x1', width + 5)
                    .attr('y1', miniHeight)
                    .attr('x2', width + 35)
                    .attr('y2', miniHeight)
                    .attr('stroke', 'black');
                var totalCount = countAllClips(lanes);
                miniLaneCount.append('text')
                    .attr('x', width + 35)
                    .attr('y', miniHeight + 15)
                    .attr('id', 'laneCountTotal')
                    .text(totalCount);

                // draw the x axis
                var xMiniAxisBottom = d3.axisBottom(xMini)
                    .ticks(14)
                    .tickFormat(d3.timeFormat('%m-%d'))
                    .tickSize(3);

                var xMiniAxisTop = d3.axisTop(xMini)
                    .ticks(7)
                    .tickFormat(d3.timeFormat('%b %Y'))
                    .tickSize(12);

                var axisNode = main.append('g')
                    .attr('transform', 'translate(0,' + mainHeight + ')')
                    .attr('class', 'main axis axisBottom')
                setMainAxisBottom(axisNode);

                axisNode = main.append('g')
                    .attr('transform', 'translate(0,0.5)')
                    .attr('class', 'main axis axisTop')
                setMainAxisTop(axisNode);

                mini.append('g')
                    .attr('transform', 'translate(0,' + miniHeight + ')')
                    .attr('class', 'mini axis axisBottom')
                    .call(xMiniAxisBottom);

                mini.append('g')
                    .attr('transform', 'translate(0,0.5)')
                    .attr('class', 'mini axis axisTop')
                    .call(xMiniAxisTop)
                    .selectAll('text')
                        .attr('dx', 5)
                        .attr('dy', 12);

                var itemRects = main.append('g')
                    .attr('clip-path', 'url(#clip)')
                    .attr('id', 'mainItems');

                mini.append('g').selectAll('miniItems')
                    .data(getPaths(items))
                    .enter().append('path')
                    .attr('class', function(d) { return 'miniItem ' + d.class; })
                    .attr('d', function(d) { return d.path; });

                // draw the selection area
                const defaultSelection = [xMini(d3.timeHour.offset(xMini.domain()[1], -12)), xMini.range()[1]+2];
                const brush = d3.brushX()
                    .extent([[0, 0.5], [width+0.5, miniHeight+0.5]])
                    .on("brush", brushed)
                    .on("end", brushended);

                if (items.length == 0) {
                    main.append('g').append('text')
                        .attr('stroke', 'red')
                        .attr('font-size', '36px')
                        .attr('x', 200)
                        .attr('y', 200)
                        .text('No Clips Found');
                }
                else {
                    // if there is no data (no time domain set), the move brush throws NaN exceptions
                    mini.append('g')
                        .attr('class', 'x brush')
                        .call(brush)
                        .call(brush.move, defaultSelection);
                }

                mini.selectAll('rect.background').remove();
                updateCacheData();

                function brushed(event) {
                    if (event.selection) {
                        chart.property('value', event.selection.map(xMini.invert, xMini).map(d3.timeDay.round));
                        chart.dispatch('input');
                        display(event);
                    }
                }

                function brushended(event) {
                    if (!event.selection) {
                        brush.move(chart.select("g.brush"), defaultSelection);
                    }
                    else {
                        display(event);
                    }
                }

                // if mouse wheel scrolled/zoomed inside the main graph, zoom in/out.
                function zoomed(event) {
                    if (event.transform.x == 0 && event.transform.y == 0) {
                        // no infinite loop, when resetting to 0,0, no more changes.
                        return;
                    }
                    // allow to 'overhang' the ends a little so user can see the first/last rect
                    var minLeftSide = new Date(d3.timeMinute(xMini.domain()[0], -30));
                    var maxRightSide = new Date(d3.timeMinute(xMini.domain()[1], +30));
                    var leftSide = xMain.domain()[0];
                    var rightSide = xMain.domain()[1];
                    // determine how much to adjust left vs. right based on mouse location
                    var pct = (event.sourceEvent.clientX / (width+margin.left));
                    if (pct < 0)
                        pct = 0;
                    if (pct > 1)
                        pct = 1;
                    // shift is by 30 minutes. some percentage on the left, some on right.
                    var leftShift = 30*pct;
                    var rightShift = 30-leftShift;
                    if (event.transform.y < 0) {
                        // zoom in by 30 minutes, unless we are at the max zoom,
                        // which is 90-120 minutes so the selection in mini can be seen
                        if (d3.timeMinute.count(leftSide, rightSide) > 120) {
                            leftSide = d3.timeMinute.offset(leftSide, leftShift);
                            rightSide = d3.timeMinute.offset(rightSide, -1*rightShift);
                        }
                    }
                    else if (event.transform.y > 0) {
                        // zoom out by 30 minutes
                        leftSide = d3.timeMinute.offset(leftSide, -1*leftShift);
                        rightSide = d3.timeMinute.offset(rightSide, rightShift);
                    }
                    // make sure it doesn't grow beyond the maximum edge boundaries
                    leftSide = new Date(Math.max(leftSide, minLeftSide));
                    rightSide = new Date(Math.min(rightSide, maxRightSide));
                    if (leftSide > rightSide) {
                        // swap them
                        rightSide = [leftSide, rightSide = leftSide][0];
                    }
                    brush.move(chart.select('g.brush'), [xMini(leftSide), xMini(rightSide)]);
                    d3.select('.mainCanvas').call(zoom.transform, d3.zoomIdentity);
                }

                // if entering a rect clip on the main graph, pulsate while selected.
                function mouseEntered(event) {
                    var item = null;
                    if (event.target) {
                        item = camIdStrToDetails.get(event.target.id.substring(3));  // strip off "id_" prefix
                        // deselect anything currently highlighted, so we only have one item highlighted
                        d3.selectAll('.highlighted').classed('highlighted', false);
                        d3.select('#' + event.target.id).classed('highlighted', true);
                    }
                    updateSummary(item);
                }

                function mouseLeft(event) {
                    var idstr = event.target ? event.target.id : 'null target?';
                    d3.selectAll('.highlighted').classed('highlighted', false);
                    // we have deselected whatever we had highlighted.
                    // if we previously CLICKED something, re-highlight that.
                    d3.selectAll('.selected')
                        .classed('highlighted', true)
                        .call(function (s) {
                             if (!s.empty()) {
                                 var id=s.attr('id').substring(3);
                                 var item=camIdStrToDetails.get(id);
                                 updateSummary(item);
                             }
                             else {
                                 updateSummary();
                             }
                         });
                }

                function mouseClicked(event) {
                    // if any (other) object currently selected, clear it.
                    d3.selectAll('.selected').classed('selected', false);
                    if ( event.target.classList.contains('mainCanvas') )
                    {
                        // the clicked-on object is the background canvas.
                        // unhighlight and deselect everything
                        d3.selectAll('.highlighted').classed('highlighted', false);
                        updateSummary();
                        return;
                    }
                    // otherwise, whatever is highlighted (mouse on, currently), remember it
                    var selection = d3.selectAll('.highlighted');
                    if (! selection.empty()) {
                        // remember it by making it also "selected"
                        selection.classed('selected', true);
                    }
                }

                function updateSummary(item) {
                    var cam = '';
                    var start = '';
                    if (item) {
                        cam = laneIdIntToDetails.get(item.lane).label;
                        start = item.start.toLocaleDateString() + ' ' + item.start.toLocaleTimeString();
                        var currImage = d3.select('.video').select('img');
                        if (currImage.empty()) {
                            d3.select('.image_loading').text('Loading...');
                            d3.select('.video').select('video').remove();
                            currImage = d3.select('.video').append('img')
                                .attr('width', 640)
                                .attr('height', 360);
                            d3.select('#video_btn')
                                .attr('onclick', 'watchVideo()');
                        }
                        currImage.attr('src', 'media/image/' + item.id);
                    }
                    else {
                        d3.select('.video').select('img').remove();
                        d3.select('.image_loading').text('Click or Hover on a recording to view');
                        d3.select('.video').select('video').remove();
                    }
                    d3.select('#camera_value').text(cam);
                    d3.select('#start_value').text(start);
                    d3.select('#duration_value').text('');
                }

                function watchVideo() {
                    var selection = d3.selectAll('.highlighted');
                    if (selection.empty()) {
                        return;
                    }
                    var id = selection.attr('id').substring(3); // strip off id_ prefix
                    d3.select('.video').select('img').remove();
                    d3.select('.image_loading').text('Loading video...');
                    video_node = d3.select('.video').select('video');
                    if (video_node.empty()) {
                        video_node = d3.select('.video').append('video');
                        video_node.attr('controls', 'true')
                            .attr('width', 640)
                            .attr('height', 360)
                            .append('source')
                                .attr('src', 'media/video/' + id);
                        setTimeout(getVideoDuration(id), 2000);
                    }
                    else {
                        video_node.attr('controls', 'true').select('source')
                            .attr('src', 'media/video/' + id);
                    }
                    //video_node.node().play();  // autoplay not allowed?
                }

                function getVideoDuration(id) {
                    return function() {
                        //console.info('Checking for duration of video ' + id);
                        var video_element = d3.select('.video').select('video');
                        if (video_element) {
                            if (video_element.select('source')) {
                                if (video_element.select('source').attr('src') == 'media/video/' + id) {
                                    // yes, same video previously selected is still here
                                    var duration = video_element.node().duration;
                                    if (duration) {
                                        var mins = Math.floor(duration / 60);
                                        var secs = Math.floor(duration % 60);
                                        var str = String(mins).padStart(2, "0");
                                        d3.select('#duration_value')
                                            .text(mins + ":" + String(secs).padStart(2, "0"));
                                        return;
                                    }
                                    else {
                                        // if we get here, we don't have a duration yet, so reschedule it
                                        setTimeout(getVideoDuration(id), 2000);
                                        //console.info('will try to get duration again in 2 seconds. ID: ' + id);
                                    }
                                }
                            }
                        }
                    }
                }

                function setMainAxisBottom(node) {
                    node.call(d3.axisBottom(xMain)
                            .ticks(12)
                            .tickFormat(d3.timeFormat('%H:%M:%S'))
                            .tickSize(3));
                }

                function setMainAxisTop(node) {
                    node.call(d3.axisTop(xMain)
                            .ticks(4)
                            .tickFormat(d3.timeFormat('%a %b %e'))
                            .tickSize(12))
                        .selectAll('text')
                            .attr('dx', 5)
                            .attr('dy', 12);
                }

                function display (event) {
                    var rects,
                        minExtent = d3.timeSecond(xMini.invert(event.selection[0])),
                        maxExtent = d3.timeSecond(xMini.invert(event.selection[1])),
                        visItems = items.filter(function (d) { return d.start < maxExtent && d.end > minExtent});

                    xMain.domain([minExtent, maxExtent]);
                    setMainAxisTop(main.select('g.axisTop'));
                    setMainAxisBottom(main.select('g.axisBottom'));

                    // upate the item rects
                    rects = itemRects.selectAll('rect')
                        .data(visItems, function (d) { return d.id; })
                        .attr('x', function(d) { return xMain(d.start); })
                        .attr('width', function(d) { return xMain(d.end) - xMain(d.start); });

                    rects.enter().append('rect')
                        .attr('x', function(d) { return xMain(d.start); })
                        .attr('y', function(d) { return yMain(d.lane) + .1 * yMain(1) + 0.5; })
                        .attr('width', function(d) { return xMain(d.end) - xMain(d.start); })
                        .attr('height', function(d) { return .8 * yMain(1); })
                        .attr('class', function(d) { return 'mainItem ' + d.cssClass; })
                        .attr('id', d => 'id_' + d.id)
                        .on('pointerenter', event => mouseEntered(event))
                        .on('pointerleave', event => mouseLeft(event))
                        .on('pointerdown', event => mouseClicked(event))
                        ;

                    rects.exit().remove()
                        .on('pointerenter', null)
                        .on('pointerleave', null);

                    var x1, y1, x2, y2;
                    x1 = 0;
                    y1 = mainHeight;
                    x2 = event.selection[0];
                    y2 = mainHeight + 40;
                    d3.select('.funnel.left').select('line')
                        .attr('x1', x1).attr('y1', y1)
                        .attr('x2', x2).attr('y2', y2);
                    x1 = width;
                    y1 = mainHeight;
                    x2 = event.selection[1];
                    y2 = mainHeight + 40;
                    d3.select('.funnel.right').select('line')
                        .attr('x1', x1).attr('y1', y1)
                        .attr('x2', x2).attr('y2', y2);

                    // we have changed the main display. If nothing is highlighted (anymore)
                    // then clear the summary fields
                    if (d3.selectAll('.highlighted').empty()) {
                        updateSummary();
                    }
                }

                // generates a single path for each item class in the mini display
                // ugly - but draws mini 2x faster than append lines or line generator
                // is there a better way to do a bunch of lines as a single path with d3?
                function getPaths(items) {
                    var paths = {}, d, offset = .5 * yMini(1) + 0.5, result = [];
                    for (var i = 0; i < items.length; i++) {
                        d = items[i];
                        if (!paths[d.cssClass]) paths[d.cssClass] = '';
                        var pixelStart = xMini(d.start);
                        var pixelStop = xMini(d.end);
                        if (pixelStop - pixelStart < 2) {
                            // needs to be visible, go to 3 pixels, 1 to the left, 1 to the right
                            pixelStart = pixelStart - 1;
                            pixelStop = pixelStart + 3;         // needs to be visible!
                        }
                        //paths[d.cssClass] += ['M',xMini(d.start),(yMini(d.lane) + offset),'H',xMini(d.end)].join(' ');
                        paths[d.cssClass] += ['M',pixelStart,(yMini(d.lane) + offset),'H',pixelStop].join(' ');
                    }

                    for (var className in paths) {
                        result.push({class: className, path: paths[className]});
                    }

                    return result;
                }

                function calculateMaxWidth(lanes) {
                    var canvas = document.createElement('canvas');
                    var context = canvas.getContext('2d');
                    var widest = 1;
                    var thisWidth = 0;
                    context.font = fontSize + ' ' + fontFamily;
                    for (var ii = 0; ii < lanes.length; ii++) {
                        thisWidth = context.measureText(lanes[ii].label).width;
                        if (thisWidth > widest) {
                            widest = thisWidth;
                        }
                    }
                    return widest;
                }

                function countAllClips(lanes) {
                    var total = 0;
                    for (var ii = 0; ii < lanes.length; ii++) {
                        total += lanes[ii].numClips;
                    }
                    return total;
                }

                // This will update data on the web page.
                // Initially just the cache information.
                // Maybe someday it will add new video clips too.
                function updateCacheData() {
                    d3.select('#clear_cache_btn').attr('onclick', 'clearCache()');
                    var cacheRequest = new XMLHttpRequest();
                    cacheRequest.open("GET", "/blink/account/" + accountId + "/media/cache", true);
                    cacheRequest.onload = function () {
                        if (cacheRequest.responseText) {
                            var data = JSON.parse(cacheRequest.responseText);
                            if (data) {
                                var sizeStr = formatSizeStr(data.image_size_bytes);
                                d3.select('#cache_images').text(data.image_count + ' (' + sizeStr + ')');
                                sizeStr = formatSizeStr(data.video_size_bytes);
                                d3.select('#cache_videos').text(data.video_count + ' (' + sizeStr + ')');
                                sizeStr = formatSizeStr(data.image_size_bytes + data.video_size_bytes);
                                var capStr = formatSizeStr(data.max_cache_bytes);
                                var pct = 100*(data.image_size_bytes + data.video_size_bytes) / data.max_cache_bytes;
                                d3.select('#cache_usage').text(pct.toFixed(1) + '% (' + sizeStr + ' / ' + capStr + ')');
                            }
                        }
                    }
                    cacheRequest.send();
                }

                function formatSizeStr(bytes) {
                    var sizeStr = '';
                    if (bytes > 1024*1024)
                        sizeStr = (bytes / 1024.0 / 1024).toFixed(1) + ' MB';
                    else if (bytes > 1024)
                        sizeStr = (bytes / 1024.0).toFixed(1) + ' KB';
                    else
                        sizeStr = bytes + ' Bytes';
                    return sizeStr;
                }

                function clearCache() {
                    var req = new XMLHttpRequest();
                    req.open("PUT", "/blink/account/" + accountId + "/media/cache/clear", false);
                    req.send();
                    updateCacheData();
                }
                </script>
                </body>
                </html>
                """);
        w.flush();
    }

    public class Clips {
        @Nullable
        List<Lane> lanes;
        @Nullable
        List<Item> items;

        public class Lane {
            @Nullable
            Integer id;
            String label;
            Integer numClips = 0;
        }

        public class Item {
            Long id;
            Integer lane;
            String start;
            String end;
            String cssClass;
            String desc;
        }
    }
}
