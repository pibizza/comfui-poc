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

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
public class ComfyUIService {

    @Inject
    @RestClient
    ComfyUIRestClient restClient;

    @ConfigProperty(name = "comfyui.host")
    String host;

    @ConfigProperty(name = "comfyui.port")
    int port;

    @ConfigProperty(name = "comfyui.workflow-file")
    String workflowFile;

    @ConfigProperty(name = "comfyui.prompt-node-id")
    String promptNodeId;

    @ConfigProperty(name = "comfyui.ws-timeout-seconds")
    long wsTimeout;

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public byte[] generate(final String prompt) {
        try {
            final String clientId = UUID.randomUUID().toString();
            final String workflowJson = Files.readString(Path.of(workflowFile));
            final Map<String, Object> workflow = mapper.readValue(workflowJson, Map.class);

            ((Map<String, Object>) ((Map<String, Object>) workflow.get(promptNodeId)).get("inputs"))
                    .put("text", prompt);

            // Connect WebSocket before submitting so we cannot miss the completion signal
            final URI wsUri = URI.create("ws://" + host + ":" + port + "/ws?clientId=" + clientId);
            final WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            final ComfyUIWebSocketClient wsClient = new ComfyUIWebSocketClient();

            final String promptId;
            try (final Session ignored = container.connectToServer(wsClient, wsUri)) {
                final Map<String, Object> body = Map.of("prompt", workflow, "client_id", clientId);
                final Map<String, Object> response = restClient.submitPrompt(body);
                promptId = (String) response.get("prompt_id");
                wsClient.setPromptId(promptId);
                wsClient.awaitCompletion(wsTimeout);
            }

            final Map<String, Object> history = restClient.getHistory(promptId);
            final Map<String, Object> outputs = (Map<String, Object>)
                    ((Map<String, Object>) history.get(promptId)).get("outputs");
            final List<Map<String, Object>> images = (List<Map<String, Object>>)
                    ((Map<String, Object>) outputs.get("9")).get("images");
            final Map<String, Object> imageInfo = images.get(0);
            final String filename = (String) imageInfo.get("filename");
            final String subfolder = (String) imageInfo.getOrDefault("subfolder", "");

            return restClient.viewImage(filename, subfolder, "output");

        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("ComfyUI image generation interrupted", e);
        } catch (final Exception e) {
            throw new RuntimeException("ComfyUI image generation failed", e);
        }
    }
}
