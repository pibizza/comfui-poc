# ComfyUI PoC — Design

## Overview

A Quarkus service that drives an iterative image-generation and prompt-refinement
loop against a locally running ComfyUI instance. QuarkusFlow orchestrates the loop;
LangChain4j (Ollama backend) provides all LLM interactions.

## Technology Stack

| Component | Artifact | Version |
|---|---|---|
| Quarkus | `io.quarkus:quarkus-bom` | managed via `quarkus-flow-bom` |
| QuarkusFlow | `io.quarkiverse.flow:quarkus-flow` | 0.12.0 |
| LangChain4j Ollama | `io.quarkiverse.langchain4j:quarkus-langchain4j-ollama` | 1.11.2 |
| WireMock (test) | `com.github.tomakehurst:wiremock-jre8` | 2.35.2 |
| AssertJ (test) | `org.assertj:assertj-core` | managed by Quarkus BOM |
| ComfyUI | — | v0.27.0 |
| Ollama | — | v0.31.1 |

Maven coordinates: `org.kie:comfui-poc`

## Package Structure

```
org.kie.comfui
├── comfyui/
│   ├── ComfyUIService.java          @ApplicationScoped — orchestrates REST + WebSocket
│   ├── ComfyUIRestClient.java       @RegisterRestClient — POST /prompt, GET /history/{id}, GET /view
│   └── ComfyUIWebSocketClient.java  @ClientEndpoint — single-use completion listener
├── domain/
│   ├── WorkflowInput.java           record: seed, maxIterations, qualityThreshold
│   ├── LoopState.java               mutable context carried through each flow iteration
│   ├── RatingResult.java            record: score (0–100), feedback
│   ├── WorkflowResult.java          record: finalImage, finalPrompt, score, iterations, terminationReason
│   └── TerminationReason.java       enum: QUALITY_REACHED | MAX_ITERATIONS_REACHED
├── agents/                          (planned — Task 4)
├── workflow/                        (planned — Task 5)
└── web/                             (planned — Task 6)
```

## Architecture

**Single workflow + dedicated `ComfyUIService`** (Approach B from the design spec).
- `PromptOptimizationWorkflow` is pure orchestration (no I/O).
- All ComfyUI I/O is isolated in `ComfyUIService`.
- All LLM calls are behind `@RegisterAiService` interfaces (Task 4).

### ComfyUI Integration

`ComfyUIService.generate(prompt)` executes a five-step sequence:

1. Load and patch the ComfyUI workflow JSON (inject prompt text into node `comfyui.prompt-node-id`).
2. **Connect WebSocket** (`ws://host:port/ws?clientId=<uuid>`) before submitting — avoids missing the completion signal if generation is fast.
3. `POST /prompt` with the patched workflow and `client_id`.
4. Block on `CountDownLatch` until ComfyUI signals `{"type":"executing","data":{"node":null}}`.
5. `GET /history/{promptId}` → extract output filename → `GET /view` → return `byte[]`.

`ComfyUIWebSocketClient` is single-use per call (one `CountDownLatch`, one volatile `promptId`).

## Data Flow

```
WorkflowInput → PromptGeneratorAgent → ComfyUIService.generate()
                                              ↓
                                    ImageRaterAgent (vision model)
                                              ↓
                          score ≥ threshold?  ──YES──→ WorkflowResult
                                    ↓ NO
                          PromptRefinerAgent → loop back
```

`terminationReason` is `QUALITY_REACHED` or `MAX_ITERATIONS_REACHED`.

## Configuration

```properties
comfyui.host=localhost
comfyui.port=8188
comfyui.workflow-file=src/main/resources/comfyui-workflow.json
comfyui.prompt-node-id=6
comfyui.ws-timeout-seconds=120

workflow.max-iterations=5
workflow.quality-threshold=75

quarkus.langchain4j.ollama.base-url=http://localhost:11434
quarkus.langchain4j.ollama.chat-model.model-id=llama3.2
quarkus.langchain4j.ollama.vision.base-url=http://localhost:11434
quarkus.langchain4j.ollama.vision.chat-model.model-id=llava
```

`ImageRaterAgent` uses `@ModelName("vision")` to route to the llava instance.
`comfyui.workflow-file` can be overridden at runtime to swap ComfyUI pipelines.

## Testing

| Level | Approach |
|---|---|
| `ComfyUIServiceTest` | `@QuarkusTest` + WireMock (HTTP) + `TestComfyUIWebSocketServer @ServerEndpoint` (WS completion signal) |
| Workflow IT | `@QuarkusTest` + `@InjectMock` on agents + WireMock (Task 5) |
| Agent IT | `@QuarkusTest` hitting real Ollama; tagged `@Tag("integration")` (Task 4) |

Test split: WireMock handles `POST /prompt`, `GET /history`, `GET /view` on port 18188; the Quarkus test container serves the `@ServerEndpoint("/ws")` on port 18189.

## Error Handling

| Scenario | Behaviour |
|---|---|
| WebSocket timeout | `TimeoutException` wrapped in `RuntimeException`; interrupt flag restored if interrupted |
| ComfyUI REST error | Exception propagates; wrapped in `RuntimeException("ComfyUI image generation failed")` |
| Max iterations hit | Normal exit — `terminationReason = MAX_ITERATIONS_REACHED` |
| Ollama unreachable | LangChain4j throws immediately; workflow instance fails |
