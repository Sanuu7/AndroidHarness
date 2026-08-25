---
name: plan
description: User asked for a plan, or PLAN mode is on. Explore, then a concrete plan.
category: software-development
---

# Plan

Explore with read-only tools, then write a step-by-step plan the user can approve.

## When to use
- PLAN mode is active
- The user said plan, design, or "don't code yet"
- The change touches more than two obvious files

## When not to use
- A one-line fix the user already specified
- You are already implementing an approved plan

## Procedure
1. `list_dir` / `search_files` / `grep` / `read_file` until you know the current shape. Use `task` for broad exploration. Check: you can name the files that will change.
2. List constraints (AGENTS.md, existing APIs, tests). Check: no invented files.
3. Write a numbered plan: each step is one action, with the file path and how you will know it worked.
4. Stop. Do not edit files while PLAN mode is on.
5. After the user approves, switch to ACT and follow the plan.

## Pitfalls
- A plan that says "refactor as needed" is not a plan.
- Do not propose tools that do not exist here (no terminal, no gh, no browser_vision).

## Verification
The user can approve or reject each step without asking what you meant.
