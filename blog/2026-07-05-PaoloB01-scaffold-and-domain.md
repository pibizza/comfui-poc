---
layout: post
title: "First Code — Scaffold and Domain Layer"
date: 2026-07-05
type: phase-update
entry_type: note
subtype: diary
projects: [comfui-poc]
tags: [quarkusflow, langchain4j, quarkus, maven]
---

Yesterday was all design. Today there's a `pom.xml`.

We ran the Quarkus Maven generator and it resolved 3.37.1 rather than the
3.21.1 the plan was written against — the plugin picks the latest stable
version at generation time. That's fine. The plan specified QuarkusFlow BOM
0.12.0 explicitly, so we added that as a second import in `dependencyManagement`
alongside the Quarkus BOM. `quarkus-langchain4j-ollama:1.11.2` and
`wiremock-jre8:2.35.2` went in as versioned deps outside the BOM.

`./mvnw compile -q` returned nothing, which is success. Two resources landed
alongside the build file: `application.properties` with the ComfyUI host,
Ollama model names, and loop control values wired to config properties; and
`comfyui-workflow.json` with the standard SD1.5 text-to-image workflow,
node 6 as the positive prompt slot.

## The Domain Layer

Five classes, all in `org.kie.comfui.domain`. Nothing clever here — just the
shared vocabulary the workflow and agents will use.

`WorkflowInput` is a record: seed string, max iterations, quality threshold.
`RatingResult` is a record: integer score, feedback string. `WorkflowResult` is
a record: image bytes, final prompt, score, iteration count, termination reason.
`TerminationReason` is a two-value enum.

`LoopState` is the only plain class. QuarkusFlow tasks mutate state in place
rather than returning new copies, so the carrier has to be mutable. I thought
about an immutable approach — return a new `LoopState` from each task — but
that would work against the framework rather than with it. Public fields, no
getters. Ugly but honest.

## The PR Workflow

I committed the scaffold straight to `main` on the first task. The next session
told me to open a PR instead. That's the right call for a project with GitHub
issues tracking the work — the PR body carries the `Closes #N` that wires the
issue closed automatically on merge, and there's a natural review gate before
anything lands on main.

New convention: `feat/<short-description>` branch, PR, merge, delete branch.
Domain objects went through as PR #8. Issue #3 closed automatically.

## Where Things Stand

Tasks 1 and 2 of 6 are done. The scaffold compiles. The domain layer is merged.
Next is Task 3: `ComfyUIRestClient`, `ComfyUIWebSocketClient`, `ComfyUIService`,
and the WireMock test that drives it. That's where the first real integration
logic appears — submitting a workflow JSON to ComfyUI and waiting on a WebSocket
for completion before fetching image bytes.

The two unknowns from Day Zero are still ahead in Task 5.
