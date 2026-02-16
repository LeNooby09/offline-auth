# CrackAuth

Server-side authentication mod for Minecraft offline-mode (cracked) servers built on the Fabric mod loader.

CrackAuth requires players to register with an invite code and log in with a password each session. Until authenticated, players are suspended in the sky and blocked from moving, chatting, breaking blocks, using items, or interacting with entities.

## Features

- **Invite-code registration** -- new players must use a valid invite code to register.
- **Password authentication** -- registered players log in with a password every session.
- **Display name override** -- players choose a display name during registration, shown in chat, tab list, and scoreboard.
- **Automatic OP authentication** -- server operators can optionally skip the login step.
- **Configurable timeouts and bans** -- adjustable auth timeout, soft-ban duration, max login attempts, and minimum password length.
- **Admin commands** -- generate and manage invite codes, list accounts, and remove players.
- **First-boot invite code** -- on a fresh install with no accounts, a one-time admin invite code is printed to the server log.

## Requirements

- Minecraft 1.21.11
- Fabric Loader 0.18.4 or newer
- Fabric API
- Fabric Language Kotlin

## Installation

1. Install Fabric Loader and Fabric API on your server.
2. Install the Fabric Language Kotlin mod.
3. Drop the CrackAuth jar into the server `mods/` folder.
4. Start the server. A config file will be generated at `config/crack-auth/config.yml` and a one-time admin invite code will be printed in the server log.
5. Use the invite code to register the first (admin) account.

## Commands

| Command | Description |
|---|---|
| `/register <invite-code> <username> <password>` | Register a new account using an invite code. |
| `/login <password>` | Log in to an existing account. |
| `/crackauth invite generate [uses]` | Generate a new invite code (admin). |
| `/crackauth invite list` | List all active invite codes (admin). |
| `/crackauth invite revoke <code>` | Revoke an invite code (admin). |
| `/crackauth accounts` | List all registered accounts (admin). |
| `/crackauth remove <username>` | Remove a registered account (admin). |

## Configuration

The configuration file is located at `config/crack-auth/config.yml` and is generated on first startup. Changes require a server restart.

| Option | Default | Description |
|---|---|---|
| `auth-timeout-seconds` | 60 | Seconds a player has to authenticate before being kicked. |
| `soft-ban-minutes` | 5 | Minutes a player is temporarily banned after an auth timeout. |
| `max-login-attempts` | 5 | Maximum failed login attempts before the player is kicked. |
| `min-password-length` | 8 | Minimum password length required for registration. |
| `sky-y` | 30000.0 | Y coordinate where unauthenticated players are held. |
| `auto-auth-ops` | true | Whether server operators are automatically authenticated on join. |
| `invite-code-length` | 10 | Length of generated invite codes (alphanumeric characters, excluding dashes). |

## License

This project is licensed under the GNU General Public License v3.0. See [LICENSE.txt](LICENSE.txt) for details.
