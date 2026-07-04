# ComfyUI PoC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a Quarkus service that iteratively generates and refines ComfyUI images using a QuarkusFlow loop driven by LangChain4j agents.

**Architecture:** A single `PromptOptimizationWorkflow` (QuarkusFlow `Flow`) orchestrates three LangChain4j agents and a `ComfyUIService` CDI bean. `LoopState` carries mutable state (prompt, image, score, iteration) through the loop. The loop exits when score ≥ threshold or iteration ≥ max.

**Tech Stack:** Quarkus, QuarkusFlow 0.12.0, quarkus-langchain4j-ollama 1.11.2, ComfyUI v0.27.0 WebSocket API, WireMock (tests).

## Global Constraints

- Java 21, Maven
- GroupId: `org.kie`, ArtifactId: `comfui-poc`
- QuarkusFlow BOM: `io.quarkiverse.flow:quarkus-flow-bom:0.12.0`
- LangChain4j Ollama: `io.quarkiverse.langchain4j:quarkus-langchain4j-ollama:1.11.2`
- Apache License 2.0 header on every Java file
- No Co-Authored-By in commit messages

---

## File Map

```
pom.xml
src/main/java/org/kie/comfui/
  domain/
    WorkflowInput.java        record: seed, maxIterations, qualityThreshold
    LoopState.java            mutable POJO: currentPrompt, currentImage, score, iteration
    RatingResult.java         record: score (int 0-100), feedback (String)
    WorkflowResult.java       record: finalImage, finalPrompt, score, iterations, terminationReason
    TerminationReason.java    enum: QUALITY_REACHED, MAX_ITERATIONS_REACHED
  comfyui/
    ComfyUIRestClient.java    @RegisterRestClient: POST /prompt, GET /history/{id}, GET /view
    ComfyUIWebSocketClient.java  @ClientEndpoint: ws://host:8188/ws
    ComfyUIService.java       @ApplicationScoped CDI bean: generate(prompt) → byte[]
  agents/
    PromptGeneratorAgent.java @RegisterAiService (default Ollama)
    ImageRaterAgent.java      @RegisterAiService @ModelName("vision")
    PromptRefinerAgent.java   @RegisterAiService (default Ollama)
  workflow/
    PromptOptimizationWorkflow.java  extends Flow
  web/
    WorkflowResource.java     @Path("/workflow") REST trigger
src/main/resources/
  application.properties
  comfyui-workflow.json       default SD1.5 text-to-image workflow
src/test/java/org/kie/comfui/
  comfyui/ComfyUIServiceTest.java     WireMock + local WS server
  workflow/PromptOptimizationWorkflowIT.java  @InjectMock agents + WireMock
```

---

## Task 1: Project Scaffold

**Files:**
- Create: `pom.xml`
- Create: `src/main/resources/application.properties`
- Create: `src/main/resources/comfyui-workflow.json`
- Create: `.gitignore`

- [ ] **Step 1: Generate Quarkus project skeleton**

```bash
mvn io.quarkus.platform:quarkus-maven-plugin:3.21.1:create \
  -DprojectGroupId=org.kie \
  -DprojectArtifactId=comfui-poc \
  -DprojectVersion=1.0.0-SNAPSHOT \
  -Dextensions="rest,rest-client,websockets-client,smallrye-health" \
  -DnoCode
```

Copy the generated `pom.xml`, `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitignore` into the repo root. Delete the generated directory after copying.

- [ ] **Step 2: Add dependencies to pom.xml**

Import the QuarkusFlow BOM (manages Quarkus version) and add all required deps:

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>io.quarkiverse.flow</groupId>
      <artifactId>quarkus-flow-bom</artifactId>
      <version>0.12.0</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependencies>
  <!-- QuarkusFlow -->
  <dependency>
    <groupId>io.quarkiverse.flow</groupId>
    <artifactId>quarkus-flow</artifactId>
  </dependency>

  <!-- LangChain4j Ollama -->
  <dependency>
    <groupId>io.quarkiverse.langchain4j</groupId>
    <artifactId>quarkus-langchain4j-ollama</artifactId>
    <version>1.11.2</version>
  </dependency>

  <!-- REST + WebSocket -->
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-rest-client</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-websockets-client</artifactId>
  </dependency>
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-smallrye-health</artifactId>
  </dependency>

  <!-- Test -->
  <dependency>
    <groupId>io.quarkus</groupId>
    <artifactId>quarkus-junit5</artifactId>
    <scope>test</scope>
  </dependency>
  <dependency>
    <groupId>com.github.tomakehurst</groupId>
    <artifactId>wiremock-jre8</artifactId>
    <version>2.35.2</version>
    <scope>test</scope>
  </dependency>
</dependencies>
```

- [ ] **Step 3: Write application.properties**

```properties
# ComfyUI
comfyui.host=localhost
comfyui.port=8188
comfyui.workflow-file=src/main/resources/comfyui-workflow.json
comfyui.prompt-node-id=6
comfyui.ws-timeout-seconds=120

# Loop control
workflow.max-iterations=5
workflow.quality-threshold=75

# Ollama — default text model
quarkus.langchain4j.ollama.base-url=http://localhost:11434
quarkus.langchain4j.ollama.chat-model.model-id=llama3.2

# Ollama — vision model (named "vision")
quarkus.langchain4j.ollama.vision.base-url=http://localhost:11434
quarkus.langchain4j.ollama.vision.chat-model.model-id=llava

# REST client for ComfyUI
quarkus.rest-client.comfyui.url=http://${comfyui.host}:${comfyui.port}
```

- [ ] **Step 4: Add default ComfyUI workflow JSON**

Save this as `src/main/resources/comfyui-workflow.json` — a standard SD1.5 text-to-image workflow. Node 6 is the positive text prompt node (the value `comfyui.prompt-node-id` refers to):

```json
{
  "3": {"class_type": "KSampler", "inputs": {"seed": 42, "steps": 20, "cfg": 7.0, "sampler_name": "euler", "scheduler": "normal", "denoise": 1.0, "model": ["4", 0], "positive": ["6", 0], "negative": ["7", 0], "latent_image": ["5", 0]}},
  "4": {"class_type": "CheckpointLoaderSimple", "inputs": {"ckpt_name": "v1-5-pruned-emaonly.ckpt"}},
  "5": {"class_type": "EmptyLatentImage", "inputs": {"batch_size": 1, "height": 512, "width": 512}},
  "6": {"class_type": "CLIPTextEncode", "inputs": {"text": "PROMPT_PLACEHOLDER", "clip": ["4", 1]}},
  "7": {"class_type": "CLIPTextEncode", "inputs": {"text": "ugly, blurry, low quality", "clip": ["4", 1]}},
  "8": {"class_type": "VAEDecode", "inputs": {"samples": ["3", 0], "vae": ["4", 2]}},
  "9": {"class_type": "SaveImage", "inputs": {"filename_prefix": "comfui-poc", "images": ["8", 0]}}
}
```

- [ ] **Step 5: Verify build compiles**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS, no errors.

- [ ] **Step 6: Commit**

```bash
git add pom.xml .gitignore mvnw mvnw.cmd .mvn/ src/main/resources/
git commit -m "feat: scaffold Quarkus project with all dependencies"
```

---

## Task 2: Domain Objects

**Files:**
- Create: `src/main/java/org/kie/comfui/domain/TerminationReason.java`
- Create: `src/main/java/org/kie/comfui/domain/WorkflowInput.java`
- Create: `src/main/java/org/kie/comfui/domain/LoopState.java`
- Create: `src/main/java/org/kie/comfui/domain/RatingResult.java`
- Create: `src/main/java/org/kie/comfui/domain/WorkflowResult.java`

**Interfaces:**
- Produces: `WorkflowInput`, `LoopState`, `RatingResult`, `WorkflowResult`, `TerminationReason` — used by Tasks 3, 4, 5, 6.

- [ ] **Step 1: Write domain classes**

```java
// TerminationReason.java
package org.kie.comfui.domain;
public enum TerminationReason { QUALITY_REACHED, MAX_ITERATIONS_REACHED }
```

```java
// WorkflowInput.java
package org.kie.comfui.domain;
public record WorkflowInput(String seed, int maxIterations, int qualityThreshold) {}
```

```java
// LoopState.java — mutable because quarkusflow mutates it across tasks
package org.kie.comfui.domain;
public class LoopState {
    public String currentPrompt;
    public byte[] currentImage;
    public int score;
    public String feedback;
    public int iteration;
    public int maxIterations;
    public int qualityThreshold;
}
```

```java
// RatingResult.java
package org.kie.comfui.domain;
public record RatingResult(int score, String feedback) {}
```

```java
// WorkflowResult.java
package org.kie.comfui.domain;
public record WorkflowResult(byte[] finalImage, String finalPrompt, int score,
                              int iterations, TerminationReason terminationReason) {}
```

- [ ] **Step 2: Compile**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/kie/comfui/domain/
git commit -m "feat: add domain objects"
```

---

## Task 3: ComfyUI Integration

**Files:**
- Create: `src/main/java/org/kie/comfui/comfyui/ComfyUIRestClient.java`
- Create: `src/main/java/org/kie/comfui/comfyui/ComfyUIWebSocketClient.java`
- Create: `src/main/java/org/kie/comfui/comfyui/ComfyUIService.java`
- Test: `src/test/java/org/kie/comfui/comfyui/ComfyUIServiceTest.java`

**Interfaces:**
- Consumes: `LoopState` (Task 2)
- Produces: `ComfyUIService.generate(String prompt) → byte[]` — used by Task 5 (workflow)

ComfyUI's WebSocket protocol: connect to `ws://host:8188/ws?clientId=<uuid>`, submit via `POST /prompt`, listen for `{"type":"executing","data":{"node":null,"prompt_id":"<id>"}}` which signals completion, then `GET /history/{promptId}` to get the output filename, then `GET /view?filename=...&subfolder=&type=output` to fetch the image bytes.

- [ ] **Step 1: Write the REST client**

```java
package org.kie.comfui.comfyui;

import io.quarkus.rest.client.reactive.ClientQueryParam;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import java.util.Map;

@RegisterRestClient(configKey = "comfyui")
public interface ComfyUIRestClient {

    @POST
    @Path("/prompt")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Object> submitPrompt(Map<String, Object> body);

    @GET
    @Path("/history/{promptId}")
    @Produces(MediaType.APPLICATION_JSON)
    Map<String, Object> getHistory(@PathParam("promptId") String promptId);

    @GET
    @Path("/view")
    @Produces("image/png")
    byte[] viewImage(@QueryParam("filename") String filename,
                     @QueryParam("subfolder") @DefaultValue("") String subfolder,
                     @QueryParam("type") @DefaultValue("output") String type);
}
```

- [ ] **Step 2: Write the WebSocket client**

```java
package org.kie.comfui.comfyui;

import jakarta.websocket.*;
import java.util.concurrent.*;

@ClientEndpoint
public class ComfyUIWebSocketClient {

    private final CountDownLatch latch = new CountDownLatch(1);
    private final String promptId;

    public ComfyUIWebSocketClient(String promptId) {
        this.promptId = promptId;
    }

    @OnMessage
    public void onMessage(String message) {
        // ComfyUI signals completion with node=null for our promptId
        if (message.contains("\"node\":null") && message.contains(promptId)) {
            latch.countDown();
        }
    }

    public void awaitCompletion(long timeoutSeconds) throws InterruptedException, TimeoutException {
        if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
            throw new TimeoutException("ComfyUI did not complete prompt " + promptId + " within " + timeoutSeconds + "s");
        }
    }
}
```

- [ ] **Step 3: Write ComfyUIService**

```java
package org.kie.comfui.comfyui;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.WebSocketContainer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@ApplicationScoped
public class ComfyUIService {

    @RestClient
    ComfyUIRestClient restClient;

    @ConfigProperty(name = "comfyui.host") String host;
    @ConfigProperty(name = "comfyui.port") int port;
    @ConfigProperty(name = "comfyui.workflow-file") String workflowFile;
    @ConfigProperty(name = "comfyui.prompt-node-id") String promptNodeId;
    @ConfigProperty(name = "comfyui.ws-timeout-seconds") long wsTimeout;

    private final ObjectMapper mapper = new ObjectMapper();

    @SuppressWarnings("unchecked")
    public byte[] generate(String prompt) {
        try {
            String clientId = UUID.randomUUID().toString();
            String workflowJson = Files.readString(Path.of(workflowFile));
            Map<String, Object> workflow = mapper.readValue(workflowJson, Map.class);

            // Inject prompt into the text node
            ((Map<String, Object>) ((Map<String, Object>) workflow.get(promptNodeId)).get("inputs"))
                .put("text", prompt);

            Map<String, Object> body = Map.of("prompt", workflow, "client_id", clientId);
            Map<String, Object> response = restClient.submitPrompt(body);
            String promptId = (String) response.get("prompt_id");

            // Connect WebSocket and wait for completion
            URI wsUri = URI.create("ws://" + host + ":" + port + "/ws?clientId=" + clientId);
            ComfyUIWebSocketClient wsClient = new ComfyUIWebSocketClient(promptId);
            WebSocketContainer container = ContainerProvider.getWebSocketContainer();
            container.connectToServer(wsClient, wsUri);
            wsClient.awaitCompletion(wsTimeout);

            // Fetch image
            Map<String, Object> history = restClient.getHistory(promptId);
            Map<String, Object> outputs = (Map<String, Object>)
                ((Map<String, Object>) history.get(promptId)).get("outputs");
            // Navigate: outputs → node "9" → images[0] → filename
            List<Map<String, Object>> images = (List<Map<String, Object>>)
                ((Map<String, Object>) outputs.get("9")).get("images");
            String filename = (String) images.get(0).get("filename");
            String subfolder = (String) images.getOrDefault("subfolder", "");

            return restClient.viewImage(filename, subfolder, "output");

        } catch (Exception e) {
            throw new RuntimeException("ComfyUI image generation failed", e);
        }
    }
}
```

- [ ] **Step 4: Write the WireMock test**

```java
package org.kie.comfui.comfyui;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class ComfyUIServiceTest {

    static WireMockServer wireMock = new WireMockServer(18188);

    @BeforeAll
    static void startWireMock() { wireMock.start(); }

    @AfterAll
    static void stopWireMock() { wireMock.stop(); }

    @Inject ComfyUIService service;

    @Test
    void generateReturnsImageBytes() {
        String promptId = "test-prompt-id";
        byte[] fakeImage = new byte[]{(byte)0x89, 0x50, 0x4E, 0x47}; // PNG magic bytes

        wireMock.stubFor(post(urlEqualTo("/prompt"))
            .willReturn(okJson("{\"prompt_id\":\"" + promptId + "\"}")));

        wireMock.stubFor(get(urlMatching("/history/" + promptId))
            .willReturn(okJson("{\"" + promptId + "\":{\"outputs\":{\"9\":{\"images\":[{\"filename\":\"test.png\",\"subfolder\":\"\",\"type\":\"output\"}]}}}}")));

        wireMock.stubFor(get(urlMatching("/view.*"))
            .willReturn(aResponse().withBody(fakeImage)));

        // Note: WebSocket completion is triggered by the WS server started in @BeforeAll
        // For simplicity in this PoC test, configure ws-timeout low and mock WS separately
        // or use a test profile that stubs the WS response.

        byte[] result = service.generate("a cat in a hat");
        assertThat(result).isNotEmpty();
    }
}
```

> Note: the WebSocket interaction requires a real or stubbed WS server. In `src/test/resources/application.properties`, override `comfyui.host=localhost` and `comfyui.port=18188` to hit WireMock. For the WebSocket, a minimal `@ServerEndpoint` test helper can emit the completion message.

- [ ] **Step 5: Run test (expect failure until WS is wired)**

```bash
./mvnw test -pl . -Dtest=ComfyUIServiceTest -q
```

- [ ] **Step 6: Commit**

```bash
git add src/main/java/org/kie/comfui/comfyui/ src/test/java/org/kie/comfui/comfyui/
git commit -m "feat: add ComfyUI REST + WebSocket integration"
```

---

## Task 4: LangChain4j Agents

**Files:**
- Create: `src/main/java/org/kie/comfui/agents/PromptGeneratorAgent.java`
- Create: `src/main/java/org/kie/comfui/agents/ImageRaterAgent.java`
- Create: `src/main/java/org/kie/comfui/agents/PromptRefinerAgent.java`

**Interfaces:**
- Produces:
  - `PromptGeneratorAgent.generate(String seed) → String`
  - `ImageRaterAgent.rate(String prompt, byte[] imageBytes) → RatingResult`
  - `PromptRefinerAgent.refine(String currentPrompt, String feedback) → String`
- Consumes: `RatingResult` (Task 2)

`ImageRaterAgent` uses `@ModelName("vision")` so quarkus-langchain4j routes it to the `llava` model. The other two use the default `llama3.2` instance.

- [ ] **Step 1: Write agent interfaces**

```java
package org.kie.comfui.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface PromptGeneratorAgent {
    @SystemMessage("You are a ComfyUI prompt engineer. Generate a detailed, descriptive image generation prompt from a short seed concept. Include style, lighting, composition, and quality tags.")
    @UserMessage("Generate a ComfyUI prompt for: {seed}")
    String generate(String seed);
}
```

```java
package org.kie.comfui.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;
import io.quarkiverse.langchain4j.ModelName;
import org.kie.comfui.domain.RatingResult;

@RegisterAiService
@ModelName("vision")
public interface ImageRaterAgent {
    @SystemMessage("You are an image quality evaluator. Given a prompt and an image, score how well the image matches the prompt on a scale of 0-100. Be strict. Return a JSON object with fields: score (integer) and feedback (string explaining what matches and what is missing).")
    @UserMessage("Prompt: {prompt}\nImage: {imageBytes}\nRate the image adherence to the prompt.")
    RatingResult rate(String prompt, byte[] imageBytes);
}
```

```java
package org.kie.comfui.agents;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface PromptRefinerAgent {
    @SystemMessage("You are a ComfyUI prompt engineer. Given the current prompt and feedback on the generated image, produce an improved prompt that addresses the issues identified.")
    @UserMessage("Current prompt: {currentPrompt}\nFeedback: {feedback}\nWrite an improved prompt.")
    String refine(String currentPrompt, String feedback);
}
```

- [ ] **Step 2: Compile**

```bash
./mvnw compile -q
```
Expected: BUILD SUCCESS.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/kie/comfui/agents/
git commit -m "feat: add LangChain4j agent interfaces"
```

---

## Task 5: Workflow

**Files:**
- Create: `src/main/java/org/kie/comfui/workflow/PromptOptimizationWorkflow.java`
- Test: `src/test/java/org/kie/comfui/workflow/PromptOptimizationWorkflowIT.java`

**Interfaces:**
- Consumes: all agents (Task 4), `ComfyUIService` (Task 3), all domain objects (Task 2)
- Produces: nothing downstream — this is the top-level orchestrator

The workflow uses `FuncDSL.function()` to call `ComfyUIService` (not an AI agent), and `FuncDSL.switchWhenOrElse()` to loop. State flows as `LoopState` through all tasks after the generator.

- [ ] **Step 1: Write the workflow**

```java
package org.kie.comfui.workflow;

import static io.serverlessworkflow.fluent.func.dsl.FuncDSL.*;

import io.quarkiverse.flow.Flow;
import io.serverlessworkflow.api.types.Workflow;
import io.serverlessworkflow.fluent.func.FuncWorkflowBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.kie.comfui.agents.*;
import org.kie.comfui.comfyui.ComfyUIService;
import org.kie.comfui.domain.*;

@ApplicationScoped
public class PromptOptimizationWorkflow extends Flow {

    @Inject PromptGeneratorAgent promptGenerator;
    @Inject ImageRaterAgent imageRater;
    @Inject PromptRefinerAgent promptRefiner;
    @Inject ComfyUIService comfyUIService;

    @ConfigProperty(name = "workflow.max-iterations") int maxIterations;
    @ConfigProperty(name = "workflow.quality-threshold") int qualityThreshold;

    @Override
    public Workflow descriptor() {
        return FuncWorkflowBuilder
            .workflow("prompt-optimization")
            .tasks(
                // 1. Expand seed → prompt; output becomes LoopState
                function("initState", (WorkflowInput input) -> {
                    LoopState state = new LoopState();
                    state.currentPrompt = promptGenerator.generate(input.seed());
                    state.maxIterations = input.maxIterations();
                    state.qualityThreshold = input.qualityThreshold();
                    state.iteration = 0;
                    state.score = 0;
                    return state;
                }, WorkflowInput.class),

                // 2. Submit to ComfyUI, get image bytes
                function("generateImage", (LoopState state) -> {
                    state.currentImage = comfyUIService.generate(state.currentPrompt);
                    return state;
                }, LoopState.class),

                // 3. Rate the image
                function("rateImage", (LoopState state) -> {
                    RatingResult rating = imageRater.rate(state.currentPrompt, state.currentImage);
                    state.score = rating.score();
                    state.feedback = rating.feedback();
                    state.iteration++;
                    return state;
                }, LoopState.class),

                // 4. Exit if quality reached or max iterations hit
                switchWhenOrElse(
                    (LoopState state) ->
                        state.score < state.qualityThreshold && state.iteration < state.maxIterations,
                    "refinePrompt",   // true → keep refining
                    "buildResult",    // false → done
                    LoopState.class
                ),

                // 5. Refine prompt and loop back
                function("refinePrompt", (LoopState state) -> {
                    state.currentPrompt = promptRefiner.refine(state.currentPrompt, state.feedback);
                    return state;
                }, LoopState.class),
                // Jump back to generateImage
                // (quarkusflow continues sequentially unless switchWhen redirects;
                //  use a second switchWhen with always-true to jump back)
                switchWhenOrElse((_s) -> true, "generateImage", "generateImage", LoopState.class),

                // 6. Build final result
                function("buildResult", (LoopState state) -> {
                    TerminationReason reason = state.score >= state.qualityThreshold
                        ? TerminationReason.QUALITY_REACHED
                        : TerminationReason.MAX_ITERATIONS_REACHED;
                    return new WorkflowResult(state.currentImage, state.currentPrompt,
                                             state.score, state.iteration, reason);
                }, LoopState.class)
            )
            .build();
    }
}
```

> **Note on the loop-back:** QuarkusFlow's `switchWhenOrElse` jumps to a named task. The unconditional `switchWhenOrElse(always-true, "generateImage", ...)` after `refinePrompt` sends control back to `generateImage`. Verify this pattern against the quarkusflow 0.12.0 docs — if `function()` tasks must be registered by name explicitly, add `.name("generateImage")` to the generate task builder.

- [ ] **Step 2: Write workflow integration test**

```java
package org.kie.comfui.workflow;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.kie.comfui.agents.*;
import org.kie.comfui.comfyui.ComfyUIService;
import org.kie.comfui.domain.*;
import org.mockito.Mockito;
import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class PromptOptimizationWorkflowIT {

    @InjectMock PromptGeneratorAgent promptGenerator;
    @InjectMock ImageRaterAgent imageRater;
    @InjectMock PromptRefinerAgent promptRefiner;
    @InjectMock ComfyUIService comfyUIService;

    @Inject PromptOptimizationWorkflow workflow;

    @Test
    void exitsOnQualityThreshold() throws Exception {
        byte[] fakeImage = new byte[]{1, 2, 3, 4};
        Mockito.when(promptGenerator.generate("a cat")).thenReturn("a fluffy cat, studio lighting");
        Mockito.when(comfyUIService.generate(Mockito.anyString())).thenReturn(fakeImage);
        // First call: score 60 (below threshold 75) → refine
        // Second call: score 80 (above threshold) → exit
        Mockito.when(imageRater.rate(Mockito.anyString(), Mockito.any()))
            .thenReturn(new RatingResult(60, "missing studio lighting"))
            .thenReturn(new RatingResult(80, "looks good"));
        Mockito.when(promptRefiner.refine(Mockito.anyString(), Mockito.anyString()))
            .thenReturn("a fluffy cat, professional studio lighting, high detail");

        WorkflowInput input = new WorkflowInput("a cat", 5, 75);
        WorkflowResult result = (WorkflowResult) workflow.execute(input);

        assertThat(result.terminationReason()).isEqualTo(TerminationReason.QUALITY_REACHED);
        assertThat(result.iterations()).isEqualTo(2);
        assertThat(result.score()).isEqualTo(80);
    }

    @Test
    void exitsOnMaxIterations() throws Exception {
        byte[] fakeImage = new byte[]{1, 2, 3, 4};
        Mockito.when(promptGenerator.generate("a dog")).thenReturn("a dog");
        Mockito.when(comfyUIService.generate(Mockito.anyString())).thenReturn(fakeImage);
        Mockito.when(imageRater.rate(Mockito.anyString(), Mockito.any()))
            .thenReturn(new RatingResult(40, "too dark"));
        Mockito.when(promptRefiner.refine(Mockito.anyString(), Mockito.anyString()))
            .thenReturn("a bright dog");

        WorkflowResult result = (WorkflowResult) workflow.execute(new WorkflowInput("a dog", 2, 90));

        assertThat(result.terminationReason()).isEqualTo(TerminationReason.MAX_ITERATIONS_REACHED);
        assertThat(result.iterations()).isEqualTo(2);
    }
}
```

- [ ] **Step 3: Run tests**

```bash
./mvnw test -Dtest=PromptOptimizationWorkflowIT -q
```
Expected: both tests PASS. Adjust the workflow DSL if quarkusflow 0.12.0 requires different loop-back syntax.

- [ ] **Step 4: Commit**

```bash
git add src/main/java/org/kie/comfui/workflow/ src/test/java/org/kie/comfui/workflow/
git commit -m "feat: implement PromptOptimizationWorkflow with loop"
```

---

## Task 6: REST Endpoint

**Files:**
- Create: `src/main/java/org/kie/comfui/web/WorkflowResource.java`

**Interfaces:**
- Consumes: `PromptOptimizationWorkflow` (Task 5), `WorkflowInput`, `WorkflowResult` (Task 2)

- [ ] **Step 1: Write the resource**

```java
package org.kie.comfui.web;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.kie.comfui.domain.WorkflowInput;
import org.kie.comfui.domain.WorkflowResult;
import org.kie.comfui.workflow.PromptOptimizationWorkflow;

@Path("/workflow")
public class WorkflowResource {

    @Inject PromptOptimizationWorkflow workflow;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response run(WorkflowInput input) throws Exception {
        WorkflowResult result = (WorkflowResult) workflow.execute(input);
        return Response.ok(new ResultDTO(
            result.finalPrompt(),
            result.score(),
            result.iterations(),
            result.terminationReason().name()
        )).build();
    }

    record ResultDTO(String finalPrompt, int score, int iterations, String terminationReason) {}
}
```

> The final image (`result.finalImage()`) is omitted from the JSON response for simplicity. Add a `GET /workflow/image/{id}` endpoint later if needed.

- [ ] **Step 2: Compile**

```bash
./mvnw compile -q
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/org/kie/comfui/web/
git commit -m "feat: add REST endpoint to trigger workflow"
```

---

## Self-Review

**Spec coverage:**
- [x] QuarkusFlow + LangChain4j-Ollama stack — Tasks 1, 4, 5
- [x] ComfyUI WebSocket integration — Task 3
- [x] Configurable workflow JSON + prompt node injection — Task 1, 3
- [x] Three agents (generator, rater, refiner) — Task 4
- [x] `@ModelName("vision")` for rater — Task 4
- [x] Loop: score ≥ threshold OR iteration ≥ max — Task 5
- [x] `TerminationReason` enum — Task 2, 5
- [x] `LoopState` carrying mutable context — Tasks 2, 5
- [x] `comfyui.prompt-node-id` config — Tasks 1, 3
- [x] WireMock test for ComfyUIService — Task 3
- [x] Workflow integration test with mocked agents — Task 5
- [x] REST trigger endpoint — Task 6

**Open questions to verify at implementation time:**
- Exact `workflow.execute(input)` API call — check quarkusflow 0.12.0 `Flow` base class
- Whether `function()` tasks in FuncDSL require explicit `.name()` for `switchWhenOrElse` to reference them by name
- `@UserMessage` + `byte[]` parameter — verify quarkus-langchain4j passes image bytes correctly to Ollama vision endpoint; may need base64 encoding or a `dev.langchain4j.data.image.Image` wrapper
