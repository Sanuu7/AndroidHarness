---
name: architecture-diagram
description: System or cloud diagram as dark-themed SVG inside a standalone HTML file.
category: design
---

# Architecture diagram

Dark HTML + inline SVG. No extra libraries.

## When to use
- System, cloud, service, or data-flow diagram

## When not to use
- UI mockups (sketch / web-design)
- Hand-drawn whiteboard feel (not supported here)

## Procedure
1. List boxes (services) and edges (calls / data). Check: names from the user or the repo, not invented SaaS.
2. `write_file` `*-architecture.html` with a dark background, grid, labeled rectangles, arrows.
3. Include a small legend if there are more than one edge style.
4. Keep it readable on a phone: large type, not 40 micro boxes.

## Pitfalls
- External icon CDNs that break offline.
- Overlapping labels.

## Verification
Opening the file shows every named component and how they connect.
