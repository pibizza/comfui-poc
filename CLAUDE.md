# CLAUDE.md

## Project Type

type: java

## Overview

ComfyUI PoC — a Quarkus service that drives an iterative image generation and
prompt refinement loop using QuarkusFlow, LangChain4j (Ollama), and the ComfyUI
WebSocket API.

- Maven: `org.kie:comfui-poc`
- License: Apache 2.0 (header required on all Java files)

## Build Commands

```bash
./mvnw compile -q          # compile only
./mvnw test -q             # run all tests
./mvnw quarkus:dev         # dev mode with live reload
./mvnw package -q          # build JAR
```

## Testing

- Unit/integration tests: `@QuarkusTest` with WireMock for ComfyUI stubs
- Agent integration tests: tagged `@Tag("integration")` — require live Ollama; skip in CI with `-Dgroups='!integration'`
- Run a single test: `./mvnw test -Dtest=ClassName -q`

## Git Conventions

- Branch: `main`
- Commit messages: plain text, no `Co-Authored-By` trailers
- Every commit referencing an issue uses `Refs #N` or `Closes #N`

## Work Tracking

**Issue tracking:** enabled
**GitHub repo:** pibizza/comfui-poc
**Changelog:** GitHub Releases (`gh release create --generate-notes` at milestones)

**Automatic behaviours (Claude follows these when this section is present):**
- Before starting any significant task, check if it spans multiple concerns.
  If it does, help break it into separate issues before beginning work.
- When staging changes before a commit, check if they span multiple issues.
  If they do, suggest splitting the commit using `git add -p`.
