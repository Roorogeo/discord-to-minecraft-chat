# Discord Webhook Chat

A small server-side [Fabric](https://fabricmc.net/) mod for **Minecraft 26.2** that relays what happens on your server to a Discord channel through a webhook:

- 💬 Player chat (plus `/say` and `/me`), posted with the player's name and head as the avatar
- 📥 Joins and 📤 leaves
- 💀 Deaths
- 🏅 Advancements, goals and 🏆 challenges
- ✅ Server start / 🛑 stop

It's a one-way relay from Minecraft to Discord. Messages typed in Discord are not sent into the game.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/server/) (0.19.5+) for Minecraft 26.2 on your server.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and this mod's jar in the server's `mods` folder.
3. In Discord, open **Channel settings → Integrations → Webhooks → New Webhook** and copy the webhook URL.
4. Start the server once to create `config/discord-webhook.json`, then paste the URL into it and restart.

Only the server needs the mod; players don't have to install anything. The mod also works in singleplayer and LAN worlds.

## Configuration

`config/discord-webhook.json`:

```json
{
  "webhookUrl": "https://discord.com/api/webhooks/...",
  "serverName": "Minecraft Server",
  "serverAvatarUrl": "",
  "playerAvatarUrl": "https://mc-heads.net/avatar/{uuid}/128",
  "sendChat": true,
  "sendJoinLeave": true,
  "sendDeaths": true,
  "sendAdvancements": true,
  "sendServerStartStop": true
}
```

| Option | Description |
| --- | --- |
| `webhookUrl` | The Discord webhook URL. The mod does nothing while this is empty. |
| `serverName` | Name shown on Discord for server events (joins, deaths, …). |
| `serverAvatarUrl` | Avatar image for server events. Leave empty to use the webhook's own avatar. |
| `playerAvatarUrl` | Avatar for chat messages. `{uuid}` and `{name}` are replaced with the player's UUID and name. |
| `send*` | Turn each kind of message on or off. |

Deaths and advancements follow the vanilla `showDeathMessages` and `announceAdvancements` game rules: if the game doesn't announce them, they're not sent to Discord either. Chat can't ping `@everyone`, `@here`, users or roles, and Discord markdown in names and messages is escaped.

## Building

You need JDK 25.

```sh
./gradlew build
```

The jar is written to `build/libs/`. Each push also builds the mod on GitHub Actions, and the jar can be downloaded from the run's artifacts.
