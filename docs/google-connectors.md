# Google connections

Cina's default Google connection uses Android's Google Identity Services authorization API and direct Google REST APIs. Users select a service, choose an account, and approve access. No user-owned Cloud project, API key, browser cookie, or pasted token is needed. Linear and custom MCP connections are retained.

## One-time publisher setup

1. Create/select a Google Cloud project owned by the Cina publisher. Configure Google Auth Platform branding, audience, support email, and consent information. Supply the required verified domain, homepage and privacy policy for public distribution.
2. Enable Gmail API, Google Drive API, Google Docs API, Google Sheets API, Google Slides API, Google Calendar API, Google Chat API, and People API. Configure the Chat application as required by Google for user-authorized Chat API access.
3. Create an **Android OAuth client** for package `dev.pocketmuse` and the SHA-1 fingerprint of the certificate used to sign the APK. Register separate clients for local debug and production certificates. If using Play App Signing, register the Play app-signing certificate, not just the upload certificate. `:app:signingReport` can show local certificate fingerprints; never commit a keystore or its passwords.
4. Configure the scopes listed below. In testing mode, add the intended Google test accounts. Complete Google's required sensitive/restricted-scope verification before public distribution. Gmail and broad Drive access can require additional review. Workspace administrators may also restrict access.
5. Install the appropriately signed build on a Google Play services device and complete the live checks below.

This device-only flow does **not** need a client secret, backend, embedded Web client ID, or `google-services.json`. Google identifies the Android client by package and signing certificate. There is no Cloud project ID or secret for users to enter. The project registration and consent review happen outside this repository and have not been completed by this code change.

## Supported tools and permissions

Every service also requests `openid` and `https://www.googleapis.com/auth/userinfo.email` to identify the connected account. Service scopes below have the prefix `https://www.googleapis.com/auth/`.

| Connection | Scope(s) | Built-in tools |
| --- | --- | --- |
| Gmail | `gmail.readonly`, `gmail.compose` | Search/read messages, create drafts, send MIME messages |
| Drive | `drive.metadata.readonly` | Search files and read metadata |
| Docs | `documents` | Read/create documents, batch updates |
| Sheets | `spreadsheets` | Read/write cell ranges, create spreadsheets |
| Slides | `presentations` | Read/create presentations, batch updates |
| Calendar | `calendar.events` | List events, create events |
| Chat | `chat.spaces.readonly`, `chat.messages` | List spaces/messages, send messages |
| Contacts | `contacts.readonly` | List names, email addresses and phone numbers |

This is a curated initial tool set, not full parity with each Google API or the hosted MCP preview. Find Docs/Sheets/Slides IDs using Drive search or a user-provided link. Large responses are capped at 12,000 characters and explicitly marked as truncated; list endpoints expose page tokens and Sheets accepts bounded ranges. A large single document/presentation can still be truncated. Gmail message bodies use the API's base64url MIME representation. Calendar creation does not explicitly request invitation notifications (`sendUpdates`); do not promise that guests were notified.

## Authentication and lifecycle

- Authorization is requested separately for each selected service. Partial consent is rejected if required scopes are missing.
- A verified account email is retrieved from Google's userinfo endpoint and stored in `SecretStore`. Renewals are pinned to that account. Each service can retain its own account.
- Access tokens remain in memory for requests. Google Play services manages its token cache and obtains new tokens via `authorize()`. Cina does not store refresh tokens or scrape browser sessions.
- The authorization result launcher belongs to the activity and the pending service uses saved state so activity recreation can deliver results to the correct service. Process death during the final account lookup can require retrying sign-in.
- Failed/cancelled sign-in leaves existing connections intact. Successful Google sign-in replaces that service's old manual MCP credential; a successful explicit manual connection switches it back to MCP. Google and MCP tools never run simultaneously for the same service.
- Disconnect deletes the local account association, disables tools and removes local credentials. It does not revoke the provider grant: Google's revoke API revokes **all** Cina scopes for that account. The UI links to Google Account connections for explicit global revocation. To switch accounts, disconnect the service, then connect again.
- Every tool uses a fixed HTTPS origin, route and method. IDs/query values are encoded; redirects are disabled; provider error bodies and tokens are not exposed to the model. A 401 clears Google's cached token. Mutations are not automatically replayed after an error; users retry through the existing approval flow.

## Validation

Automated tests cover service-scoped permissions, fixed hosts, path/query injection, missing inputs, mutation bodies, unique tool names, and HTTPS routes. Local validation on 2026-10-14: `:app:assembleDebug` succeeded; all 69 unit tests passed, including five new connector tests; `:app:lintDebug` passed with no errors (style and existing project warnings remain). Live Google authorization and API calls have not been validated because publisher registration/test-account consent is still required.

Live release checks (require registered client and test accounts):

- Connect each service; confirm the selected account and permission summary. Reject part of consent and confirm it is not marked newly connected.
- Cancel sign-in, rotate during consent, and recreate the activity; confirm a result cannot connect a different service.
- Read a known item and approve a small test mutation where supported. Verify its result in Google; do not send messages to real recipients as a smoke test.
- Reopen after an hour and confirm fresh access without pasted tokens. Revoke in Google Account connections and confirm Cina asks for authorization again.
- Disconnect and confirm tools disappear; reconnect another account and verify account isolation.
- Verify Linear, GitHub, existing manual Google MCP connections and a custom MCP endpoint still behave as before.

References: [Android authorization](https://developer.android.com/identity/authorization), [Google API scopes](https://developers.google.com/identity/protocols/oauth2/scopes), [OAuth verification](https://developers.google.com/identity/protocols/oauth2/production-readiness/sensitive-scope-verification).
