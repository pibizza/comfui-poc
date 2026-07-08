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

import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.OnMessage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// NOT thread-safe — single-use per WebSocket session; create one instance per generate() call
@ClientEndpoint
public class ComfyUIWebSocketClient {

    private final CountDownLatch latch = new CountDownLatch(1);
    // Assigned after connection is established; volatile for cross-thread visibility
    private volatile String promptId;

    @OnMessage
    public void onMessage(final String message) {
        // promptId may be null if the completion fires before setPromptId is called;
        // in that case any "node":null message is accepted to avoid missing the signal
        if (message.contains("\"node\":null") &&
                (promptId == null || message.contains(promptId))) {
            latch.countDown();
        }
    }

    public void setPromptId(final String promptId) {
        this.promptId = promptId;
    }

    public void awaitCompletion(final long timeoutSeconds) throws InterruptedException, TimeoutException {
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new TimeoutException(
                    "ComfyUI did not complete prompt " + promptId + " within " + timeoutSeconds + "s");
        }
    }
}
