# GitHub login setup for AndroidHarness

The Android login screen and this small server work together. They are not live until you register an OAuth App, deploy the server over HTTPS, and build AndroidHarness with its public configuration.

## 1. Register the app on GitHub

Open https://github.com/settings/applications/new and enter:

| Field | Value |
| --- | --- |
| Application name | AndroidHarness |
| Homepage URL | https://github.com/Sanuu7/AndroidHarness |
| Application description | A coding agent on Android. Connect GitHub to work with repositories and pull requests. |
| Authorization callback URL (release APK) | `com.androidharness.app.oauth://github/callback` |
| Additional callback URL (debug APK) | `com.androidharness.app.debug.oauth://github/callback` |

Use exact callback matching; leave wildcard matching and device flow disabled. Keep expiring user access tokens enabled. Register the application, copy its **Client ID**, and generate a **Client secret**. Store the secret only in your hosting provider's secret/environment settings. Never add it to Android build configuration, source control, or chat.

GitHub's instructions: [registering an OAuth App](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/creating-an-oauth-app), [authorization and refresh](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps).

## 2. Deploy this server

Use a Node.js or Docker host that supplies an HTTPS URL. Set the service root directory to `backend/github-oauth`. It has no third-party package dependencies and needs no database.

- Runtime: Node.js 22 or newer.
- Start command: `npm start` (or build the included Dockerfile).
- Health check: `/health`.
- Port: provided by the host as `PORT`, otherwise 8080.

Set these server environment values:

| Variable | Value |
| --- | --- |
| `GITHUB_CLIENT_ID` | Client ID from step 1 |
| `GITHUB_CLIENT_SECRET` | Secret from step 1, stored as a hosting secret |
| `GITHUB_REDIRECT_URIS` | `com.androidharness.app.oauth://github/callback,com.androidharness.app.debug.oauth://github/callback` |

The host must terminate HTTPS before forwarding to this server. Do not enable request/response body logging: authorization codes, PKCE verifiers, and access/refresh tokens pass through this service. The server itself does not store or log them. Configure edge rate limiting for public deployment; the built-in 30 requests/minute limit uses socket IPs, so a reverse proxy can cause requests to share one limit. The service does not trust forwarded IP headers.

## 3. Configure and build the Android app

Set **only these public values** as environment variables or Gradle properties:

```text
GITHUB_CLIENT_ID=your-client-id
GITHUB_AUTH_BACKEND=https://your-deployed-login-service
```

The backend URL must point to the server root, without `/exchange` or `/refresh`. For example, build with:

```sh
./gradlew :app:assembleRelease -PGITHUB_CLIENT_ID=your-client-id -PGITHUB_AUTH_BACKEND=https://your-deployed-login-service
```

Unset configuration intentionally shows an explanatory message instead of a nonfunctional login button. Existing saved credentials remain usable, and logout remains available.

## 4. Verify the real round trip

Install the configured APK. In Settings → GitHub, select **Continue with GitHub**, authorize access, and confirm the app returns to Settings with your account name. Check private-repo access, git push/pull, and `gh auth status` with an account/repository intended for testing. Then check browser denial, cancelling and restarting login, account switching, and returning after Android kills the app process. Verify token renewal updates Git and gh, and logout clears their credentials. Logout is local; revoke the OAuth grant separately in GitHub's authorized applications if needed.

Automated checks:

```sh
node --test backend/github-oauth/server.test.mjs
./gradlew :app:testDebugUnitTest --tests 'com.androidharness.app.data.github.GitHubOAuthProtocolTest'
```

The app requests repository access and token renewal, with optional workflow, gist, organization-read and repository-deletion scopes. It saves credentials in existing Android Keystore-backed encrypted preferences. Renewal runs on app startup and periodically while the process is alive; connectivity is needed to renew an expired token. The server keeps the client secret and forwards code exchanges/refreshes only to GitHub's fixed token endpoint. Browser redirects carry a temporary code and state, never an access token.
