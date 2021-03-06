/*
 * Copyright (c) 2013 Nimbits Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS,  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either expressed or implied.  See the License for the specific language governing permissions and limitations under the License.
 */
package com.nimbits.server.api;

import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;
import com.google.appengine.api.taskqueue.TaskOptions;
import com.google.appengine.api.taskqueue.TaskOptions.Method;
import com.nimbits.client.enums.Action;
import com.nimbits.client.enums.Parameters;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import static com.google.appengine.api.taskqueue.TaskOptions.Builder.withUrl;

@SuppressWarnings("serial")
public class SendAllMessagesServlet extends BaseServlet {

    private final Logger log = Logger.getLogger(SendAllMessagesServlet.class.getName());

    /**
     * Processes the request to add a new message.
     */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
            throws IOException, ServletException {
        StringBuilder status = new StringBuilder();
        String email = req.getParameter(Parameters.email.getText());
        String json = req.getParameter(Parameters.json.getText());
        String a = req.getParameter(Parameters.action.getText());
        Action action = Action.get(a);
        if (action == null) {
            action = Action.update;
        }
        List<String> devices = Datastore.getDevices(email);
        if (email != null) {
            status.append(email);
        }
        else {
            status.append("Email was null");
        }
        if (json != null) {
            status.append(json);
            byte[] b = json.getBytes("UTF-8");
            int bytes = b.length;
            float kb = bytes / 1024;
            if (kb > 4.0) {
                status.append("message was too large");
                devices.clear();
            }
        }
        else {
            status.append("json was null");
        }

        if (devices.isEmpty()) {
            status.append("Message ignored as there is no device registered!");
        } else {
            Queue queue = QueueFactory.getQueue("gcm");
            // NOTE: check below is for demonstration purposes; a real application
            // could always send a multicast, even for just one recipient
            if (devices.size() == 1) {
                // send a single message using plain post
                String device = devices.get(0);
                queue.add(withUrl("/send")
                        .param(SendMessageServlet.PARAMETER_DEVICE, device)
                        .param(Parameters.json.getText(), json)
                        .param(Parameters.email.getText(), email)
                        .param(Parameters.action.getText(), action.getCode())
                );
                status.append("Single message queued for registration id " + device);
            } else {
                // send a multicast message using JSON
                // must split in chunks of 1000 devices (GCM limit)
                int total = devices.size();
                List<String> partialDevices = new ArrayList<String>(total);
                int counter = 0;
                int tasks = 0;
                for (String device : devices) {
                    counter++;
                    partialDevices.add(device);
                    int partialSize = partialDevices.size();
                    if (partialSize == Datastore.MULTICAST_SIZE || counter == total) {
                        String multicastKey = Datastore.createMulticast(partialDevices, email);
                        logger.fine("Queuing " + partialSize + " devices on multicast " +
                                multicastKey);
                        TaskOptions taskOptions = TaskOptions.Builder
                                .withUrl("/send")
                                .param(SendMessageServlet.PARAMETER_MULTICAST, multicastKey)
                                .param(Parameters.json.getText(), json)
                                .param(Parameters.email.getText(), email)
                                .param(Parameters.action.getText(), action.getCode())
                                .method(Method.POST);
                        queue.add(taskOptions);
                        partialDevices.clear();
                        tasks++;
                    }
                }
                status.append("Queued tasks to send " + tasks + " multicast messages to " +
                        total + " devices");
            }
        }
        log.info(status.toString());
        req.setAttribute(HomeServlet.ATTRIBUTE_STATUS, status.toString());
        getServletContext().getRequestDispatcher("/home").forward(req, resp);
    }

}
