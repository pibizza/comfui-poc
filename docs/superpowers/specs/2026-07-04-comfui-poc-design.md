# ComfyUI PoC — Design Spec

**Date:** 2026-07-04
**Status:** Approved

## Overview

A Quarkus service that uses QuarkusFlow to drive an iterative image-generation loop against a locally running ComfyUI instance. LangChain4j (Ollama backend) provides all LLM interactions. The loop generates a prompt, submits it to ComfyUI, retrieves the result via WebSocket, rates it with a vision model, and refines the prompt — repeating until the image reaches a quality threshold or a maximum number of iterations is exhausted.

## Stack

| Component | Artifact | Version |
|---|---|---|
| Quarkus | `io.quarkus:quarkus-bom` | managed by `quarkus-flow-bom:0.12.0` |
| QuarkusFlow | `io.quarkiverse.flow:quarkus-flow` | 0.12.0 |
| LangChain4j (Ollama) | `io.quarkiverse.langchain4j:quarkus-langchain4j-ollama` | 1.11.2 |
| ComfyUI | — | v0.27.0 |
| Ollama | — | v0.31.1 |

Maven coordinates: `org.kie:comfui-poc`

## Architecture

Approach B: single workflow + dedicated `ComfyUIService`. The `Flow` class is pure orchestration; all ComfyUI I/O is isolated in `ComfyUIService`; all LLM calls are behind `@RegisterAiService` interfaces.

### Package Structure

```
org.kie.comfui
├── workflow/
│   └── PromptOptimizationWorkflow.java   ← single Flow class
├── agents/
│   ├── PromptGeneratorAgent.java          ← @RegisterAiService, text model
│   ├── ImageRaterAgent.java               ← @RegisterAiService, vision model
│   └── PromptRefinerAgent.java            ← @RegisterAiService, text model
├── comfyui/
│   ├── ComfyUIService.java                ← CDI bean, orchestrates REST + WebSocket
│   ├── ComfyUIRestClient.java             ← Quarkus REST client (POST /prompt, GET /history, GET /view)
│   └── ComfyUIWebSocketClient.java        ← Quarkus WebSocket client (ws://host:8188/ws)
├── domain/
│   ├── WorkflowInput.java                 ← seed, maxIterations, qualityThreshold
│   ├── LoopState.java                     ← currentPrompt, currentImage, currentScore, iteration (mutable context)
│   ├── RatingResult.java                  ← score (0–100) + textual feedback
│   └── WorkflowResult.java                ← finalImage, finalPrompt, score, iterations, terminationReason
└── web/
    └── WorkflowResource.java              ← REST endpoint to trigger a run
```

## Data Flow

```
WorkflowInput { seed, maxIterations, qualityThreshold }
    │
    ▼
agent(PromptGeneratorAgent)
    expands seed into a rich ComfyUI prompt text
    │ prompt: String
    ▼
ComfyUIService.generate(prompt, workflowJson)
    1. load workflow JSON from file, inject prompt into text node (by node ID)
    2. POST /prompt  → { prompt_id }
    3. listen on ws://host:8188/ws until "execution_complete" for that prompt_id
    4. GET /history/{prompt_id} → extract image filename
    5. GET /view?filename=...  → return image bytes
    │ image: byte[]
    ▼
agent(ImageRaterAgent)
    vision model scores image vs prompt: RatingResult { score, feedback }
    │
    ▼
switchWhen(score >= qualityThreshold OR iteration >= maxIterations)
    ├── true  → WorkflowResult { finalImage, finalPrompt, score, iterations, terminationReason }
    └── false → agent(PromptRefinerAgent)
                    takes current prompt + feedback → refined prompt
                    └──────────────────────────────── loop back to ComfyUIService.generate
```

`terminationReason` is an enum: `QUALITY_REACHED` or `MAX_ITERATIONS_REACHED`.

## Configuration

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

# Ollama — text model (generator + refiner); default named instance
quarkus.langchain4j.ollama.base-url=http://localhost:11434
quarkus.langchain4j.ollama.chat-model.model-id=llama3.2

# Ollama — vision model (rater); named "vision", injected via @ModelName("vision")
quarkus.langchain4j.ollama.vision.base-url=http://localhost:11434
quarkus.langchain4j.ollama.vision.chat-model.model-id=llava
```

The workflow JSON ships in `src/main/resources/comfyui-workflow.json` as a default. Override `comfyui.workflow-file` at runtime to swap ComfyUI pipelines without recompiling.

`comfyui.prompt-node-id` identifies the text-conditioning node in the ComfyUI workflow JSON where the generated prompt is injected. Node `6` is the default for the standard SD1.5 example workflow; override for other pipelines.

`ImageRaterAgent` is annotated `@ModelName("vision")` so LangChain4j routes it to the vision-capable Ollama model. `PromptGeneratorAgent` and `PromptRefinerAgent` use the default (unnamed) instance.

## Error Handling

| Scenario | Behaviour |
|---|---|
| ComfyUI unreachable at startup | Exception thrown; `/q/health` reports DOWN |
| WebSocket timeout (no `execution_complete`) | Exception thrown with `prompt_id` logged; workflow instance fails |
| Rater returns unparseable score | LangChain4j retries once; if still failing, workflow instance fails |
| Max iterations hit without threshold | Normal exit — `terminationReason = MAX_ITERATIONS_REACHED` |
| Ollama unreachable | LangChain4j throws immediately; workflow instance fails |

No circuit breakers or dead-letter queues in the PoC scope.

## Testing

| Level | Approach |
|---|---|
| **Agent tests** | `@QuarkusTest` hitting real Ollama; tagged `@Tag("integration")` to skip in CI without Ollama. Validates parse correctness of `RatingResult` and prompt strings. |
| **ComfyUIService tests** | `@QuarkusTest` with WireMock stubbing REST endpoints and a local WebSocket server emitting `execution_complete`. Validates the full HTTP+WebSocket sequence. |
| **Workflow integration test** | `@QuarkusTest` with `@InjectMock` on agent interfaces + WireMock for ComfyUI. Drives loop through two iterations then exits on threshold. Asserts `WorkflowResult` fields and `terminationReason`. |

## GitHub Setup

- Repository: `kiegroups/comfui-poc` (public)
- Branch strategy: `main` trunk
- cc-praxis docs: design spec (this file), ADRs for key decisions, blog entry, handover on session close
- No CI pipeline in PoC scope (can be added later)
