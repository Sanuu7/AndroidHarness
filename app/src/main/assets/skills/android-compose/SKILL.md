---
name: android-compose
description: Jetpack Compose UI change. State, theming, previews. No XML-first guesses.
category: android
---

# Android Compose

Prefer existing theme tokens, remember / ViewModel state, and small composables.

## When to use
- Screens, sheets, cards, theming, Material 3 in this app or another Compose project

## When not to use
- XML View system (unless the file is already XML)
- A crash with a stack (android-crash first)

## Procedure
1. `grep` the existing theme (`MaterialTheme.colorScheme`, `AppHeader`). Check: you will reuse it.
2. `read_file` the nearest similar screen. Match that structure.
3. Edit with `edit_file`. Keep state out of random remember if a ViewModel already owns it.
4. Do not add a new dependency for something Material 3 already has.

## Pitfalls
- Hard-coded colors that ignore dynamic color.
- Nested Scaffold / extra paddings that double the existing header.

## Verification
The screen compiles and matches neighboring screens' spacing and type.
