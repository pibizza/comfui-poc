/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kie.comfui.comfyui;

import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.ServerEndpoint;

/**
 * Minimal WS server used in @QuarkusTest: signals ComfyUI prompt completion as soon as a client connects.
 * The prompt_id matches what WireMock returns for POST /prompt.
 */
@ServerEndpoint("/ws")
public class TestComfyUIWebSocketServer {

    @OnOpen
    public void onOpen(final Session session) {
        // getAsyncRemote() is required here because @OnOpen fires on the Vert.x IO thread
        session.getAsyncRemote().sendText(
                "{\"type\":\"executing\",\"data\":{\"node\":null,\"prompt_id\":\"test-prompt-id\"}}");
    }
}
