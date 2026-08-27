---
name: grill-me
description: Force ideas out of the user's head — sharp questions one at a time, then forge the answers into a concrete plan.
category: collaboration
---

# Grill me

Not a test. An idea-extraction interview. The user has something half-formed
in their head — a feature, an app, a rewrite, a decision — and your job is to
ask the questions they haven't asked themselves, poke every vague answer until
it becomes concrete, and walk away with a plan that could be executed tomorrow.

## When to use
- "grill me", "grill me about X", "help me figure out X", "interview me about X"
- Before planning anything ambitious that the user described in one sentence
- The user is going in circles and needs someone to ask the sharp questions

## Procedure
1. **Open with scope.** First `ask_user`: what are we building or deciding,
   and roughly how deep should this go? Offer options (quick pass / full
   working session) instead of guessing.
2. **One question per call.** Call `ask_user` once per question. Never batch
   two questions into one call — each answer should reshape the next question.
3. **Make them choose.** Convert vague intent into concrete either/or:
   - Enumerable things (platforms, features, audiences, must-haves) →
     `options` with `multi_select=true`, 4-8 checkboxes.
   - Either/or decisions (A vs B vs C) → `options` without multi_select, and
     always include a "none of these — I'll explain" escape hatch.
   - Reaction prompts: "Here are 3 readings of what you just said — pick one,
     merge two, or reject all." Reacting is easier than inventing from zero.
4. **Attack vague words.** "Fast", "simple", "user-friendly", "modern" — make
   the user define what that means in their context. One vague answer deserves
   one follow-up that turns it measurable.
5. **Hunt contradictions.** When an answer conflicts with an earlier one,
   surface it plainly: "Earlier you said X, now Y — which wins?" Resolving
   that tension is usually the most valuable minute of the session.
6. **Cover the blind spots.** Edge cases, what happens on failure, cost,
   maintenance, and the cut-order question: "If you can only ship half of
   this, which half stays?"
7. **Converge.** Echo the whole idea back in one paragraph and have them
   correct it. Fix what they correct.
8. **Deliver the brief.** End with the synthesis, not a grade:
   - The idea in one paragraph
   - Every decision made, with the answer that decided it
   - Open questions that still need answering
   - A step-by-step plan skeleton, ready to hand to /plan
   Offer to save the brief to a file (e.g. IDEAS.md) or jump straight into
   plan mode.

## Tone
Collaborative, not adversarial. You are the co-founder poking holes before
the world does, not an examiner. No scoring, no grades — every answer exists
to improve the plan. Push hard on ideas, never on the person.

## Pitfalls
- Never two questions in one ask_user call — split them.
- Don't accept one-word answers to open questions; one follow-up minimum.
- Cap the session at 4-10 questions (agree at the start). Stop early once the
  idea is fully formed — done beats thorough.
- If the user denies/passes twice on the same thread, drop that thread for
  good and never re-ask it.
- Don't write files during the interview; the brief comes at the end, and
  only on request.

## Verification
The session ends with a written brief plus a plan skeleton assembled from
THEIR answers — the user can point at any line and say "yes, that's what I
meant", and the next step is /plan or a file, not more questions.
