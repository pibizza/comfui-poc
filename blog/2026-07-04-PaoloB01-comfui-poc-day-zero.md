---
layout: post
title: "Prompt, Rate, Refine — Building a ComfyUI Feedback Loop"
date: 2026-07-04
type: day-zero
entry_type: note
subtype: diary
projects: [comfui-poc]
tags: [quarkusflow, langchain4j, comfyui, ollama, agentic]
---

The idea is simple to describe and interesting to build. Take a short seed — "a
cat in a hat" — expand it into a proper ComfyUI prompt via an LLM, submit to
ComfyUI, retrieve the image over WebSocket, have a vision model score how well
the image matches the original seed, refine the prompt based on the feedback,
and repeat. Stop when the image is good enough or you've run out of tries.

That's the loop. I want to build it with QuarkusFlow and LangChain4j.

## Why QuarkusFlow

I've been watching QuarkusFlow for a while. It implements the CNCF Serverless
Workflow spec on top of Quarkus, and the FuncDSL makes agentic loops feel
natural — the newsletter-drafter example does exactly this kind of
iterate-and-evaluate pattern. I wanted a real PoC to test whether it holds up
when the evaluation step is visual and the I/O involves a WebSocket to an
external GPU process.

## Three Agents, One Vision Model

Three LangChain4j agents, all backed by Ollama — nothing leaves the machine:

1. **PromptGeneratorAgent** — expands a seed into a rich ComfyUI prompt. Text
   model (llama3.2).
2. **ImageRaterAgent** — receives the prompt and the generated image, scores
   adherence 0–100 with feedback. Vision model (llava), wired via
   `@ModelName("vision")`.
3. **PromptRefinerAgent** — takes the current prompt and the rater's feedback,
   returns an improved prompt. Text model (llama3.2).

The rater being a vision LLM was the key decision. I considered CLIP scores —
more objective, but they need a Python sidecar or a Java binding that doesn't
really exist yet. A vision model through LangChain4j is two interfaces and a
config entry.

## The Architecture We Settled On

I brought Claude in to work through the options. We considered three approaches:
everything inline in a single `Flow`, a workflow with a dedicated
`ComfyUIService` CDI bean, and two separate workflows. We went with the middle
option.

The `Flow` class stays pure orchestration. `ComfyUIService` owns the full
ComfyUI conversation: inject the prompt into the workflow JSON at the right node
ID, POST to `/prompt`, wait on WebSocket for `execution_complete`, fetch image
bytes via `GET /view`. Clean seam. Each side can be tested independently without
the other.

The ComfyUI workflow JSON loads from a configurable file path at runtime — the
only mutation the app makes before submission is injecting the current prompt
into node 6 (the default SD1.5 text-conditioning node). Swap the file, swap the
pipeline, no recompile.

## What We Don't Know Yet

Two things are flagged in the implementation plan as unknowns against QuarkusFlow
0.12.0:

- Whether `function()` tasks need an explicit `.name()` call for
  `switchWhenOrElse` to reference them by name when looping back.
- Whether quarkus-langchain4j 1.11.2 accepts `byte[]` directly in `@UserMessage`
  to an Ollama vision endpoint, or needs a `dev.langchain4j.data.image.Image`
  wrapper.

Both will surface in Task 5. If either assumption is wrong the fix is localised
— either the DSL call or the agent interface, not both.

## Six Tasks, Seven Issues

Claude researched the current versions — QuarkusFlow 0.12.0, quarkus-langchain4j
1.11.2, ComfyUI v0.27.0, Ollama v0.31.1. The design spec is at
`docs/superpowers/specs/2026-07-04-comfui-poc-design.md`. The plan breaks the
work into six tasks sequenced by dependency. Seven GitHub issues are open on
`pibizza/comfui-poc`: one epic (#1, Create ComfyUI PoC 1.0) and six children
(#2–#7).

No code yet. Just decisions with a clear enough shape to start.
