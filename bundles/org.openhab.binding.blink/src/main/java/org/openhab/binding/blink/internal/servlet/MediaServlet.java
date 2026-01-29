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
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.Map;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.openhab.binding.blink.internal.MediaManager;
import org.openhab.binding.blink.internal.dto.BlinkEvents;
import org.openhab.binding.blink.internal.handler.AccountHandler;
import org.osgi.service.http.HttpService;
import org.osgi.service.http.NamespaceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The {@link MediaServlet} class provides the servlet for outputting a camera's current Media.
 *
 * @author Matthias Oesterheld - Initial contribution
 */
@NonNullByDefault
public class MediaServlet extends HttpServlet {

    public static final long serialVersionUID = 666L;

    private final Logger logger = LoggerFactory.getLogger(MediaServlet.class);
    private final HttpService httpService;
    private final AccountHandler accountHandler;
    private final String servletUrl;
    private final String accountId;

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
            if (pathInfo.equals("/clips/blink")) {
                sendClipsFromBlink(response);
            } else {
                PrintWriter w = response.getWriter();
                w.println("Error: unrecognized path " + request.getPathInfo());
            }
        }
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
     *          class: "css class name", desc: "description here" },
     *        ...
     *        ]
     * }
     *
     * In items:
     *     id = a unique item number
     *     laneId = a lane id, must match an id value from the lanes object
     *     start/end = date/time strings representing the duration of the recording
     *     class = a CSS class name for the drawn rectangle, e.g. "new", "viewed", "deleted"
     *     desc = a description of the event (not clear what I would use this for)
     *
     * @param response
     * @throws IOException
     */
    private void sendClipsFromBlink(HttpServletResponse response) throws IOException {
        PrintWriter w = response.getWriter();
        MediaManager mediaManager = accountHandler.getMediaManager();
        response.addHeader("content-type", "application/json");
        w.println("{");
        w.println("  \"lanes\": [");
        Map<String, Long> camNameToCamId = mediaManager.getCamerasToIds();
        Map<Long, Integer> camIdToLane = new HashMap<>();
        int laneNumber = 0;
        for (String name : camNameToCamId.keySet()) {
            Long camId = camNameToCamId.get(name);
            // of course it's not null, these are iterated keys in the for loop.
            if (camId != null) {
                camIdToLane.put(camId, laneNumber);
                w.println("    { \"id\": " + laneNumber + ", \"label\": \"" + name + "\"},");
                laneNumber++;
            }
        }
        w.println("    { \"id\": " + laneNumber + ", \"label\": \"Unknown\"}");
        int unknownLaneNumber = laneNumber;
        w.println("  ],");
        w.println("  \"items\": [");
        boolean first = true;
        for (BlinkEvents.Media rec : mediaManager.getAllMotionEvents()) {
            if (!first) {
                w.println(",");
            }
            first = false;
            w.print("    { \"id\": ");
            w.print(rec.id);
            w.print(", \"lane\": ");
            if (camIdToLane.get(rec.device_id) != null) {
                w.print(camIdToLane.get(rec.device_id));
            } else {
                w.print(unknownLaneNumber);
            }
            w.print(", \"start\": \"");
            w.print(rec.created_at.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().toString());
            w.print("\", \"end\": \"");
            // TODO figure out current recording duration
            w.print(rec.created_at.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime().plusSeconds(30));
            w.print("\", \"class\": \"");
            if (!rec.watched) {
                w.print("new");
            } else {
                w.print("viewed");
            }
            if (rec.deleted) {
                w.print(" deleted");
            }
            w.print("\" }");
        }
        w.println("\n  ]");
        w.println("}");
    }

    private void sendBasePage(HttpServletResponse response) throws IOException {
        PrintWriter w = response.getWriter();
        response.addHeader("content-type", "text/html");
        w.println("<html>\n<head>\n<title>Blink Media for account " + accountId + "</title>");
        w.println("""
                <script type="text/javascript" src="https://d3js.org/d3.v7.js"></script>
                <style>
                .chart {
                    shape-rendering: crispEdges;
                    font-family: Tahoma, Verdana, Arial, sans-serif;
                    font-size: 12px;
                }

                .mini text {
                    font: 9px sans-serif;
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

                .brush .extent {
                    stroke: gray;
                    fill: blue;
                    fill-opacity: .165;
                }
                </style>
                </head>
                <body>
                <script type="module">
                function reviveDate(key, value) {
                    if (key==="start" || key==="end") {
                        return typeof value === 'string' ? new Date(value) : value;
                    };
                    return value;
                }
                var xmlhttp = new XMLHttpRequest();
                """);
        w.println("xmlhttp.open(\"GET\", \"/blink/account/" + accountId + "/media/clips/blink\", false);");
        w.println("""
                xmlhttp.send();
                console.log('recorded clips:', xmlhttp.responseText);
                var data = JSON.parse(xmlhttp.responseText, reviveDate);
                var lanes = data.lanes;
                var items = data.items;
                var now = new Date();

                const leftAxisTextWidth = calculateMaxWidth(lanes);
                const margin = {top: 20, right: 15, bottom: 15, left: leftAxisTextWidth+10};
                const width = 960 - margin.left - margin.right;
                const miniHeight = lanes.length * 12 + 50;
                const mainHeight = lanes.length * 20 + 50;
                //const mainHeight = height - miniHeight - 50;
                //const height = 500 - margin.top - margin.bottom;
                const height = mainHeight + miniHeight + 50;

                var xMini = d3.scaleTime()
                        .domain([d3.min(items, d => d.start), d3.max(items, d => d.end)])
                        .range([1, width-2]);

                var xMain = d3.scaleTime().range([1, width-2]);

                var ext = d3.extent(lanes, function(d) { return d.id; });
                var yMain = d3.scaleLinear().domain([ext[0], ext[1] + 1]).range([0, mainHeight]);
                var yMini = d3.scaleLinear().domain([ext[0], ext[1] + 1]).range([0, miniHeight]);

                var chart = d3.select('body')
                    .append('svg:svg')
                    .attr('width', width + margin.right + margin.left)
                    .attr('height', height + margin.top + margin.bottom)
                    .attr('class', 'chart');

                chart.append('defs').append('clipPath')
                    .attr('id', 'clip')
                    .append('rect')
                        .attr('width', width)
                        .attr('height', mainHeight);

                var main = chart.append('g')
                    .attr('transform', 'translate(' + margin.left + ',' + margin.top + ')')
                    .attr('width', width)
                    .attr('height', mainHeight)
                    .attr('class', 'main');

                var mini = chart.append('g')
                    .attr('transform', 'translate(' + margin.left + ',' + (mainHeight + 60) + ')')
                    .attr('width', width)
                    .attr('height', miniHeight)
                    .attr('class', 'mini');
                // draw the lanes for the main chart
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
                // draw the x axis
                var xMiniAxisBottom = d3.axisBottom(xMini)
                    .ticks(20)
                    .tickFormat(d3.timeFormat('%m-%d'))
                    .tickSize(3);

                var xMiniAxisTop = d3.axisTop(xMini)
                    .ticks(10)
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

                // draw the items
                var itemRects = main.append('g')
                    .attr('clip-path', 'url(#clip)');

                mini.append('g').selectAll('miniItems')
                    .data(getPaths(items))
                    .enter().append('path')
                    .attr('class', function(d) { return 'miniItem ' + d.class; })
                    .attr('d', function(d) { return d.path; });

                // draw the selection area
                const defaultSelection = [xMini(d3.utcHour.offset(xMini.domain()[1], -12)), xMini.range()[1]+2];
                const brush = d3.brushX()
                    .extent([[0, 0.5], [width+0.5, miniHeight+0.5]])
                    .on("brush", brushed)
                    .on("end", brushended);

                mini.append('g')
                    .attr('class', 'x brush')
                    .call(brush)
                    .call(brush.move, defaultSelection)
                    ;

                mini.selectAll('rect.background').remove();


                function brushed(event) {
                    if (event.selection) {
                        chart.property('value', event.selection.map(xMini.invert, xMini).map(d3.utcDay.round));
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
                        .attr('class', function(d) { return 'mainItem ' + d.class; });

                    rects.exit().remove();
                }

                // generates a single path for each item class in the mini display
                // ugly - but draws mini 2x faster than append lines or line generator
                // is there a better way to do a bunch of lines as a single path with d3?
                function getPaths(items) {
                    var paths = {}, d, offset = .5 * yMini(1) + 0.5, result = [];
                    for (var i = 0; i < items.length; i++) {
                        d = items[i];
                        if (!paths[d.class]) paths[d.class] = '';
                        var pixelStart = xMini(d.start);
                        var pixelStop = xMini(d.end);
                        if (pixelStop - pixelStart < 2) {
                            // needs to be visible, go to 3 pixels, 1 to the left, 1 to the right
                            pixelStart = pixelStart - 1;
                            pixelStop = pixelStart + 3;         // needs to be visible!
                        }
                        //paths[d.class] += ['M',xMini(d.start),(yMini(d.lane) + offset),'H',xMini(d.end)].join(' ');
                        paths[d.class] += ['M',pixelStart,(yMini(d.lane) + offset),'H',pixelStop].join(' ');
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
                    context.font = '12px Tahoma';
                    for (var ii = 0; ii < lanes.length; ii++) {
                        thisWidth = context.measureText(lanes[ii].label).width;
                        if (thisWidth > widest) {
                            widest = thisWidth;
                        }
                    }
                    return widest;
                }

                </script>
                </body>
                </html>
                """);
        w.close();
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
}
