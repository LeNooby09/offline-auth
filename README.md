# OfflineAuth

Server-side authentication mod for Minecraft offline-mode (cracked) servers built on the Fabric mod loader.

OfflineAuth requires players to register with an invite code and log in with a password each session. Until authenticated, players are suspended in the sky and blocked from moving, chatting, breaking blocks, using items, or interacting with entities.

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
- **First-boot invite code** — on a fresh install with no accounts, a one-time admin invite code is printed to the server log. The first account registered with this code is automatically granted web dashboard admin permissions.
- **Command aliases** — `/r` for `/register`, `/l` for `/login`, `/ls` for `/login_as`.
- **Web dashboard** — optional embedded web dashboard for managing accounts, invite codes, sessions, and bans through a browser. Supports role-based access: dashboard admins get full control, regular users see basic stats only.

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
| `/register <invite-code> <username> <password>` | Register a new account using an invite code. |
| `/login <password>` | Log in to the account linked to this Minecraft client. |
| `/login_as <username> <password>` | Log in to an account by username (links the Minecraft client to that account). |
| `/changepassword <old_password> <new_password>` | Change your password (must be authenticated). |

### Admin Commands

All admin commands require owner-level permissions.

| Command | Description |
|---|---|
| `/offlineauth generate [max_uses]` | Generate a new invite code (default: 1 use). |
| `/offlineauth list` | List all active invite codes. |
| `/offlineauth revoke <code>` | Revoke an invite code. |
| `/offlineauth createuser <username> <password>` | Create a new account without an invite code. |
| `/offlineauth deleteuser <username>` | Delete a registered account. |
| `/offlineauth rename <username> <new_username>` | Rename a registered account. |
| `/offlineauth reload` | Hot-reload the configuration file. |

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
