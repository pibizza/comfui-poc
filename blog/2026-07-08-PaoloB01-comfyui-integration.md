---
layout: post
title: "WebSockets, WireMock, and Three Agent Stubs"
date: 2026-07-08
type: phase-update
entry_type: note
subtype: diary
projects: [comfui-poc]
tags: [comfyui, websockets, wiremock, langchain4j, quarkus]
---

Three days between sessions. The domain layer and scaffold were merged; today
was about the integration that actually talks to ComfyUI.

## The Five-Step Dance

`ComfyUIService.generate()` does five things: load and patch the workflow JSON
(inject the prompt text into node 6), open a WebSocket, submit the prompt via
REST, wait for ComfyUI to signal completion, then fetch the image bytes from
the history endpoint.

The plan had the WebSocket connection happening after the REST submission. We
reversed it — connect first, submit second. If ComfyUI finishes fast enough,
the completion signal fires before the WebSocket is even listening. A race
condition baked right into the original design. The fix is a one-line reorder,
but the kind of thing you only catch when you think about what happens under
load with a warm model cache.

The WebSocket client itself is disposable: one `CountDownLatch`, one volatile
`promptId`, used once and discarded. The `promptId` is set after submission
but the `@OnMessage` handler accepts any `"node":null` message if `promptId`
is still null — a belt-and-suspenders approach for the same race window.

## The Test Setup

WireMock can't do WebSockets. So the test splits the protocols: WireMock on
port 18188 stubs the three REST endpoints (`POST /prompt`, `GET /history`,
`GET /view`), while the Quarkus test container serves a `@ServerEndpoint("/ws")`
on port 18189 that fires the completion signal immediately on connect.

That `@ServerEndpoint` was the first surprise. `@OnOpen` runs on the Vert.x IO
thread in Quarkus, and `getBasicRemote().sendText()` throws
`IllegalStateException: Cannot use the basic remote from an IO thread`. The
Jakarta WebSocket spec says `getBasicRemote()` is valid in `@OnOpen`, but
Quarkus dispatches it on the event loop where blocking calls are forbidden.
`getAsyncRemote()` fixed it.

The dependency change was small but worth noting: `quarkus-websockets-client`
became `quarkus-websockets` — the superset that includes server support,
needed for the `@ServerEndpoint` test helper.

## Code Review Catch

The review flagged one real issue: `InterruptedException` was being swallowed
inside a broad `catch (Exception e)` without restoring the thread's interrupt
status. If Quarkus shutdown interrupts the thread mid-generation, the interrupt
flag would be silently cleared. Split into two catch blocks — `InterruptedException`
restores the flag, everything else wraps as before.

## Three Interfaces, No Implementation

Task 4 was fast. Three `@RegisterAiService` interfaces:

```java
@RegisterAiService
public interface PromptGeneratorAgent {
    @SystemMessage("...")
    @UserMessage("Generate a ComfyUI prompt for: {seed}")
    String generate(String seed);
}
```

`PromptGeneratorAgent` and `PromptRefinerAgent` use the default Ollama model.
`ImageRaterAgent` gets `@ModelName("vision")` to route to llava. Quarkus
generates the CDI beans at build time — compile is the test.

The plan's known unknown about whether `byte[]` works in `@UserMessage` for
Ollama vision is still open. That's a Task 5 problem.

## Where Things Stand

Tasks 3 and 4 are merged (PRs #9 and #10, Issues #4 and #5 closed). The
`docs/DESIGN.md` is in place now — created alongside the ComfyUI integration
commit. Next is Task 5: the `PromptOptimizationWorkflow` itself, the QuarkusFlow
`Flow` class with the loop and `switchWhenOrElse` branching. That's where the
FuncDSL questions from Day Zero finally get answered.
