---
name: spike
description: Unknown approach. Time-box an experiment before committing to a design.
category: software-development
---

# Spike

Time-box an experiment. Learn, then throw away or promote. Do not quietly ship the experiment as production.

## When to use
- Two approaches look equally plausible
- You have not used this library / API on this project
- The user said "try", "prototype", or "see if"

## When not to use
- The path is already obvious (just implement)
- You are debugging a known failure (systematic-debugging)

## Procedure
1. State the question in one sentence and a time box (one turn of focused work). Check: a yes/no you can answer.
2. Build the smallest experiment (`write_file` a throwaway file or a branch of logic). Check: it runs.
3. Record what you learned (API shape, perf, blocker). Check: a short note, not a novel.
4. Ask the user whether to keep, rewrite cleanly, or drop it. Check: they choose.
5. If dropping, delete the spike files.

## Pitfalls
- A spike that grows into the real feature without a rewrite leaves mess.
- Do not commit spike files unless the user asks.

## Verification
You answered the question and the workspace is either clean or the user kept the experiment on purpose.
