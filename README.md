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
- **Configurable timeouts and bans** — adjustable auth timeout, soft-ban duration, max login attempts, and minimum password length.
- **Admin commands** — generate and manage invite codes, create/delete/rename accounts, and hot-reload config.
- **First-boot invite code** — on a fresh install with no accounts, a one-time admin invite code is printed to the server log.
- **Command aliases** — `/r` for `/register`, `/l` for `/login`, `/ls` for `/login_as`.

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
5. Use the invite code to register the first (admin) account.

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

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE.txt](LICENSE.txt) for details.
