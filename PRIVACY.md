# Privacy Policy for AndroidHarness

Last updated: September 6, 2026

AndroidHarness runs on your device. It is a tool that connects to services you
choose and configure yourself. I built it so that your data stays between you
and those services. This page explains what the app handles and what it does
with it.

## What the app stores on your device

Everything the app needs to work is stored locally: your chats and agent
history, files you open or edit through the file manager, your settings, and
the credentials you enter. Credentials such as API keys, GitHub tokens, and
sign-in tokens for MCP servers are kept in encrypted storage that uses the
Android Keystore. Chats and files remain local unless you use a feature that sends them to a service as described below.

## What the app sends, and where

When you use the agent, the app sends your prompts and the context needed to answer
them (which can include file contents and command output from your device) to
the AI provider you configured, for example OpenAI, Anthropic, or any other
OpenAI-compatible endpoint you entered. Web search queries go to the search
service you configured, or to public search engines when no API key is set.
If you connect a GitHub account, you create a personal access token on
GitHub and paste it into the app. The app checks the token by calling
GitHub's API with it and saves it only after GitHub confirms it belongs to
you. The token is sent only to GitHub: for the check, for repository
requests, and for Git operations. There is no separate login server between
you and GitHub. If you add an MCP server, the app talks to that server directly, and
any OAuth sign-in happens between you and that server.

Which services receive data, and what keys they use, is entirely up to you.
If you remove or change a provider, server, or key in settings, the app stops
using it.

## What I collect

The app has no analytics, advertising, crash reporting, or telemetry.

## Backups

App data may be included in backups handled by your device's backup system
and your Google account settings. You can turn that off in your device's
backup settings.

## Children

The app is a developer and power-user tool and is not directed at children
under 13.

## Changes

If the app's data handling ever changes, I will update this page and the
change will be reflected in the app's store listing.

## Contact

Questions or concerns: open an issue at
https://github.com/Sanuu7/AndroidHarness/issues
