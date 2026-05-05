# OfflineAuth

Server-side authentication mod for Minecraft offline-mode (cracked) servers built on the Fabric mod loader.

OfflineAuth requires players to register with an invite code and log in with a password each session. Until authenticated, players are suspended in the sky and blocked from moving, chatting, breaking blocks, using items, or interacting with entities.

The mod has two **mutually exclusive** authentication modes selected at boot via the `bluesky-enabled` config flag:

- **Password mode (default)** — the existing invite-code + password flow described below. No Bluesky configuration is required.
- **Bluesky mode (opt-in)** — players authenticate via Bluesky/ATProto OAuth, with whitelist membership backed by a Bluesky list. The legacy `/register`, `/login`, `/login_as`, `/changepassword`, and `/2fa` commands are runtime-disabled (they remain registered for tab-completion but politely refuse) and players use `/bluesky` instead. See [Bluesky (opt-in) auth](#bluesky-opt-in-auth) below.

Switching modes requires a restart, just like other config keys; the legacy command source files are kept untouched so flipping `bluesky-enabled` back to `false` reverts to password mode without further changes.

## Features

- **Invite-code registration** — new players must use a valid invite code to register.
- **Password authentication** — registered players log in with a password every session.
- **Display name override** — players choose a display name during registration, shown in chat, tab list, and scoreboard.
- **Account switching** — players can link multiple Minecraft clients to the same account using `/login_as`.
- **Change password** — authenticated players can change their password in-game.
- **Session persistence** — optionally keep players authenticated across reconnects from the same IP.
- **Automatic OP authentication** — server operators can optionally skip the login step.
- **Registration rate limiting** — per-IP limits on registration attempts, cooldowns, and maximum accounts per IP.
- **Login lockout** — exponential backoff after repeated failed login attempts.
- **Hidden join message** — optionally suppress the "Player joined the game" broadcast until the player authenticates.
- **Configurable timeouts and bans** — adjustable auth timeout, soft-ban duration, max login attempts, and minimum password length.
- **Admin commands** — generate and manage invite codes, create/delete/rename accounts, and hot-reload config.
- **First-boot invite code** — on a fresh install with no accounts, a one-time admin invite code is printed to the server log. The first account registered with this code is automatically granted web dashboard admin permissions. (Skipped when booting straight into Bluesky mode.)
- **Command aliases** — `/r` for `/register`, `/l` for `/login`, `/ls` for `/login_as`, `/b` for `/bluesky`.
- **Web dashboard** — optional embedded web dashboard for managing accounts, invite codes, sessions, and bans through a browser. Supports role-based access: dashboard admins get full control, regular users see basic stats only.
- **Bluesky (ATProto) OAuth** *(opt-in)* — players authenticate via their Bluesky account; whitelist comes from an operator-supplied `app.bsky.graph.list`. See [Bluesky (opt-in) auth](#bluesky-opt-in-auth).

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.4 or newer
- Fabric API
- Fabric Language Kotlin

## Installation

1. Install Fabric Loader and Fabric API on your server.
2. Install the Fabric Language Kotlin mod.
3. Drop the OfflineAuth jar into the server `mods/` folder.
4. Start the server. A config file will be generated at `config/offline-auth/config.yml` and a one-time admin invite code will be printed in the server log.
5. Use the invite code to register the first (admin) account. This account automatically receives web dashboard admin permissions.

## Commands

### Player Commands

| Command | Description |
|---|---|
| `/register <invite-code> <username> <password>` | Register a new account using an invite code. *Disabled in Bluesky mode.* |
| `/login <password>` | Log in to the account linked to this Minecraft client. *Disabled in Bluesky mode.* |
| `/login_as <username> <password>` | Log in to an account by username (links the Minecraft client to that account). *Disabled in Bluesky mode.* |
| `/changepassword <old_password> <new_password>` | Change your password (must be authenticated). *Disabled in Bluesky mode.* |
| `/2fa setup\|confirm\|disable\|verify\|status` | Manage TOTP-based 2FA on your account. *Disabled in Bluesky mode (Bluesky provides 2FA itself).* |
| `/bluesky` | Start a Bluesky OAuth login flow — sends a clickable chat link plus QR code. *Only registered when Bluesky mode is enabled.* |

### Admin Commands

All admin commands require owner-level permissions.

| Command | Description |
|---|---|
| `/offlineauth generate [max_uses]` | Generate a new invite code (default: 1 use). *Disabled in Bluesky mode.* |
| `/offlineauth list` | List all active invite codes. *Disabled in Bluesky mode.* |
| `/offlineauth revoke <code>` | Revoke an invite code. *Disabled in Bluesky mode.* |
| `/offlineauth createuser <username> <password>` | Create a new account without an invite code. *Disabled in Bluesky mode.* |
| `/offlineauth deleteuser <username>` | Delete a registered account. |
| `/offlineauth rename <username> <new_username>` | Rename a registered account. |
| `/offlineauth reload` | Hot-reload the configuration file. |

When a command is "disabled in Bluesky mode", running it sends `§cThis command is disabled — Bluesky auth is enabled. Use /bluesky.` instead of an "unknown command" error. The commands remain registered so tab-completion still lists them, and they re-enable automatically when `bluesky-enabled` is flipped back to `false`.

## Configuration

The configuration file is located at `config/offline-auth/config.yml` and is generated on first startup. Changes can be hot-reloaded with `/offlineauth reload`.

| Option | Default | Description |
|---|---|---|
| `auth-timeout-seconds` | 60 | Seconds a player has to authenticate before being kicked. |
| `soft-ban-minutes` | 5 | Minutes a player is temporarily banned after an auth timeout. |
| `max-login-attempts` | 5 | Maximum failed login attempts before the player is kicked. |
| `min-password-length` | 8 | Minimum password length required for registration. |
| `sky-y` | 30000.0 | Y coordinate where unauthenticated players are held. |
| `auto-auth-ops` | true | Whether server operators are automatically authenticated on join. |
| `invite-code-length` | 10 | Length of generated invite codes (alphanumeric characters, excluding dashes). |
| `session-persistence-enabled` | false | Whether players stay authenticated across reconnects from the same IP. |
| `session-duration-minutes` | 1440 | How long a session persists in minutes (24 hours by default). |
| `max-register-attempts-per-ip` | 5 | Maximum registration attempts per IP before cooldown kicks in. |
| `register-cooldown-seconds` | 60 | Cooldown in seconds after max registration attempts from the same IP. |
| `max-accounts-per-ip` | 3 | Maximum number of accounts that can be registered from a single IP (0 = unlimited). |
| `login-lockout-base-seconds` | 30 | Base lockout duration in seconds after max failed login attempts (doubles each time). |
| `login-lockout-max-seconds` | 3600 | Maximum lockout duration in seconds (cap for exponential backoff). |
| `hide-join-message-until-login` | false | Whether to hide the "Player joined the game" message until the player authenticates. |
| `web-dashboard-enabled` | false | Whether to enable the embedded web dashboard for account management. |
| `web-dashboard-port` | 8080 | Port for the web dashboard HTTP server. |
| `web-dashboard-bind-address` | 127.0.0.1 | Bind address for the web dashboard (`127.0.0.1` = localhost only, `0.0.0.0` = all interfaces). |
| `web-dashboard-domain` | *(empty)* | Public domain/IP for QR code URLs (e.g. `example.com`). If empty, uses the bind address. |
| `database-type` | sqlite | Database backend: `sqlite` (local file) or `postgresql` (remote, for multi-server setups). |
| `bluesky-enabled` | false | **Opt-in:** enable Bluesky (ATProto) OAuth login. When `true`, legacy password commands are runtime-disabled and players use `/bluesky`. |
| `bluesky-whitelist-list` | *(empty)* | Bluesky list reference: an `at://...app.bsky.graph.list/...` URI **or** a `https://bsky.app/profile/<handle-or-did>/lists/<rkey>` URL. Members of this list may log in. |
| `bluesky-public-url` | *(empty)* | Public **HTTPS** base URL the OAuth callback is reachable at (e.g. `https://auth.example.com`). Loopback `http://127.0.0.1` is allowed for local dev only. |
| `bluesky-client-name` | OfflineAuth Minecraft Server | Display name shown to users on the Bluesky consent screen. |
| `bluesky-scope` | atproto rpc:app.bsky.actor.getProfile?aud=did:web:api.bsky.app#bsky_appview | OAuth scopes requested. Must include `atproto`. The default uses the modern `rpc:...` lexicon-permission grammar and grants only the `app.bsky.actor.getProfile` AppView call OfflineAuth needs. The legacy `transition:generic` grant is **deprecated** and **rejected at startup**: configs that still contain it fall back to password mode with a migration hint in the server log. Operators stuck on an older PDS that only understands `transition:generic` should pin a previous OfflineAuth release rather than re-enable the deprecated grant. |
| `bluesky-list-cache-seconds` | 300 | How long the resolved Bluesky list members are cached before re-fetching (5 minutes by default). |
| `bluesky-pairing-token-ttl-minutes` | 10 | How long an in-game `/bluesky` pairing token stays valid before it expires. |

## Bluesky (opt-in) auth

When `bluesky-enabled: true`, the mod boots into **Bluesky mode** for the lifetime of the process.

### How it works

1. A player joins; the chat hint tells them to run `/bluesky`.
2. `/bluesky` generates a one-time pairing token bound to their UUID and replies with a clickable chat link plus a QR-code link to `https://<bluesky-public-url>/bluesky/login/<token>`.
3. The browser opens a handle entry form, the user types their Bluesky handle, and the embedded web server starts an ATProto OAuth flow (PAR + DPoP).
4. The OAuth callback verifies the issuer, exchanges the code, looks up the user's DID against the configured Bluesky list, and (on a hit) finalizes the in-game login. On a miss the user sees a clear "you're not on the whitelist" page.
5. First successful login with a given DID auto-creates a Minecraft account using the sanitized first segment of the Bluesky handle as the username (with a numeric suffix on collision); subsequent logins re-authenticate the same account by DID.

### Required setup

- Set `bluesky-enabled: true`.
- Set `bluesky-whitelist-list` to either an AT-URI (`at://did:plc:.../app.bsky.graph.list/<rkey>`) or a bsky.app URL (`https://bsky.app/profile/<handle-or-did>/lists/<rkey>`). Curate this list directly in the Bluesky app — the server caches it for `bluesky-list-cache-seconds` and refreshes on misses, so changes propagate without a restart.
- Set `bluesky-public-url` to the **HTTPS** URL where the embedded web server is reachable (typically through a reverse proxy with TLS termination — see [Security Notes](#security-notes)). Loopback `http://127.0.0.1` is allowed for local dev only; ATProto rejects non-loopback `http://` `client_id`s.
- Restart the server.

The `application_type` field in the published `oauth-client-metadata.json` is chosen automatically: `web` for HTTPS `bluesky-public-url` values, and `native` for loopback (`http://127.0.0.1`, `http://localhost`, `http://[::1]`) dev setups. This matches the ATProto OAuth provider's validation rules — operators do not need to configure it manually.

### Same-site PDS support

The upstream `@atproto/oauth-provider` (used by every PDS based on the official Bluesky reference implementation) guards its `/oauth/authorize` endpoint with a `Sec-Fetch-Site` allow-list of `same-origin`, `cross-site`, `none` (see [`packages/oauth/oauth-provider/src/router/create-authorization-page-middleware.ts`](https://github.com/bluesky-social/atproto/blob/main/packages/oauth/oauth-provider/src/router/create-authorization-page-middleware.ts)). The value `same-site` is intentionally absent. A plain server-side `302` from OfflineAuth on `https://atpcraft.example.com` to `https://pds.example.com/oauth/authorize` would be tagged `Sec-Fetch-Site: same-site` by the browser (both hosts share the registrable domain `example.com`) and rejected by the PDS with a generic *"An unknown error occurred"* page.

To make same-registrable-domain deployments work, OfflineAuth's `POST /bluesky/login/<token>` returns a `200 OK` HTML interstitial (with `Referrer-Policy: no-referrer`) instead of a `302`. The interstitial creates a hidden `<iframe sandbox="allow-scripts allow-top-navigation">` — note the **absence** of `allow-same-origin` — and from inside that iframe sets `window.top.location.href = <authorizeUrl>`. Because the sandboxed iframe has an opaque origin, the browser tags the resulting top-level navigation as `Sec-Fetch-Site: cross-site`, which the PDS allow-list accepts. A visible `<a rel="noreferrer">` fallback link covers users with JavaScript disabled or strict iframe-blocking extensions; a manual click on a `noreferrer` link is also tagged `cross-site`. As a result, configurations like `bluesky-public-url: https://atpcraft.lenooby09.tech` with players on `pds.lenooby09.tech` are supported out of the box. This technique is ported from the sibling [`intermediate-oauth`](https://git.lenooby09.tech/LeNooby09/intermediate-oauth) project's [`OAuthProvider.kt`](https://git.lenooby09.tech/LeNooby09/intermediate-oauth/blob/main/src/main/kotlin/tech/lenooby09/provider/OAuthProvider.kt) (`post("/authorize/login")` handler), where it has been in production use to bridge Bluesky to Forgejo's OIDC.

### Auto-versioning of the OAuth `client_id` URL

The published `client_id` URL is suffixed with a content-derived `?v=<hash>` query (e.g. `https://auth.example.com/oauth-client-metadata.json?v=ab12cd34`). The hash is a stable digest of the metadata-relevant fields (`bluesky-scope`, `bluesky-client-name`, `bluesky-public-url`); it changes only when one of those fields changes.

This exists because Authorization Servers (e.g. `@atproto/oauth-provider`-based PDSes) cache the client metadata document keyed by `client_id` URL with a default TTL of 10 minutes. Without the `?v=<hash>` suffix, an operator who edits `bluesky-scope` and restarts within that 10-minute window would hit a stale cache and get errors like `invalid_scope: Scope "transition:generic" is not declared in the client metadata` until the cache expires. The realistic case today is an operator migrating *off* the deprecated `transition:generic` grant onto the modern `rpc:...` default — the same cache-bust suffix makes that migration land instantly. The version tag forces auth servers to treat any config change as a brand-new client and re-fetch the document immediately, while keeping the URL stable across restarts when nothing relevant has changed.

Operators don't need to configure this — the version is computed automatically from the loaded config. Any code path that handcrafts a `client_id` URL outside the mod (e.g. an external admin tool) must use the same `client_id` URL the mod publishes, including the `?v=` query.

### Misconfiguration safety

If `bluesky-enabled: true` is paired with an empty/non-HTTPS `bluesky-public-url`, an empty `bluesky-whitelist-list`, or a `bluesky-scope` that still contains the deprecated `transition:generic` grant, the mod logs a warning and **falls back to password mode** rather than crashing — the server stays usable while the operator fixes the config. Operators on an older PDS that only understands `transition:generic` should pin a previous OfflineAuth release rather than re-enable the deprecated grant.

### Reverting

To switch back, set `bluesky-enabled: false` and restart. Password-mode commands are immediately re-enabled. Pre-existing password accounts continue to work; Bluesky-created accounts (with `password_hash = NULL`) lose their login path until the flag is flipped back, but their `bluesky_links` rows are preserved.

## Web Dashboard

OfflineAuth includes an optional embedded web dashboard for managing accounts, invite codes, sessions, and soft bans through a browser.

### Enabling the Dashboard

Set `web-dashboard-enabled: true` in `config/offline-auth/config.yml` and reload with `/offlineauth reload` or restart the server. The dashboard will be available at `http://<bind-address>:<port>/` (default: `http://127.0.0.1:8080/`).

### Permissions

The dashboard uses role-based access stored in the database:

- **Admin users** see all management tabs (accounts, invite codes, sessions, soft bans) and can perform all operations including creating/deleting accounts, managing invite codes, granting/revoking dashboard admin, and reloading config.
- **Regular users** can log in but only see basic server stats (total accounts, online players, active sessions, invites, bans). They cannot access any management features.

The first account registered with the SYSTEM-generated first-boot invite code is automatically granted dashboard admin. Admins can grant or revoke dashboard admin permissions for other accounts from the Accounts tab.

### REST API

The dashboard exposes a REST API for programmatic access. Authenticate by sending a `POST /api/login` request and use the returned token as a `Bearer` token in subsequent requests.

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| POST | `/api/login` | None | Authenticate with username and password. Returns a session token and admin status. |
| POST | `/api/logout` | Any | Invalidate the current session token. |
| GET | `/api/me` | Any | Get the currently authenticated username and admin status. |
| GET | `/api/stats` | Any | Get server statistics. |
| GET | `/api/accounts` | Admin | List all accounts. |
| GET | `/api/accounts/:id` | Admin | Get account details and linked UUIDs. |
| POST | `/api/accounts` | Admin | Create a new account (JSON body: `username`, `password`). |
| DELETE | `/api/accounts/:id` | Admin | Delete an account. |
| PUT | `/api/accounts/:id/rename` | Admin | Rename an account (JSON body: `newUsername`). |
| PUT | `/api/accounts/:id/password` | Admin | Change an account's password (JSON body: `newPassword`). |
| PUT | `/api/accounts/:id/admin` | Admin | Grant or revoke dashboard admin (JSON body: `isAdmin`). |
| GET | `/api/invites` | Admin | List active invite codes. |
| POST | `/api/invites` | Admin | Generate a new invite code (JSON body: `maxUses`). |
| DELETE | `/api/invites/:code` | Admin | Revoke an invite code. |
| GET | `/api/sessions` | Admin | List active sessions. |
| GET | `/api/bans` | Admin | List active soft bans. |
| DELETE | `/api/bans/:ip` | Admin | Remove a soft ban. |
| POST | `/api/config/reload` | Admin | Hot-reload the configuration file. |

### Security Notes

- The dashboard runs over **HTTP** by default. For production use, place it behind a reverse proxy (e.g., nginx or Caddy) with HTTPS.
- Bind to `127.0.0.1` (default) to restrict access to localhost. Only change to `0.0.0.0` if you have a reverse proxy or firewall in place.

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE.txt](LICENSE.txt) for details.
