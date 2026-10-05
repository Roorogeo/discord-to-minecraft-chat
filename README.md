# Discord Webhook Chat

A small server-side [Fabric](https://fabricmc.net/) mod for **Minecraft 26.2** that relays what happens on your server to a Discord channel through a webhook:

- 💬 Player chat (plus `/say` and `/me`), posted with the player's name and head as the avatar
- 📥 Joins and 📤 leaves
- 💀 Deaths
- 🏅 Advancements, goals and 🏆 challenges
- ✅ Server start / 🛑 stop

With an optional Discord bot, players can also **link their Discord account** to their Minecraft account and **run Minecraft commands from Discord** (see [Running commands from Discord](#running-commands-from-discord)).

Messages typed in Discord are not sent into the game chat.

## Installation

1. Install [Fabric Loader](https://fabricmc.net/use/server/) (0.19.5+) for Minecraft 26.2 on your server.
2. Put [Fabric API](https://modrinth.com/mod/fabric-api) and this mod's jar in the server's `mods` folder.
3. In Discord, open **Channel settings → Integrations → Webhooks → New Webhook** and copy the webhook URL.
4. Start the server once to create `config/discord-webhook.json`, then paste the URL into it and restart.

Only the server needs the mod; players don't have to install anything. The mod also works in singleplayer and LAN worlds.

## Running commands from Discord

This part needs a Discord bot, because webhooks can only post messages.

1. Go to the [Discord Developer Portal](https://discord.com/developers/applications), create an application, open **Bot** and copy the token. No privileged intents are needed.
2. Invite the bot to your server: under **OAuth2 → URL Generator**, tick `bot` and `applications.commands`, then open the generated link.
3. Put the token in `botToken` in `config/discord-webhook.json`. Also set `guildId` to your Discord server's ID (right-click the server icon → *Copy Server ID*, with Developer Mode on) so the commands show up immediately and only work in your server.
4. Restart the Minecraft server.

Then, to link an account:

1. In Minecraft, run `/discord link`. You get a 6-digit code that is valid for 5 minutes.
2. In Discord, run `/link code:<code>`. After 5 wrong codes you have to wait 15 minutes, so codes can't be guessed.

Once linked, run `/mc command:<command>` in Discord, for example `/mc command:whitelist add Steve`. The command runs as your Minecraft player, and its output is shown only to you in Discord.

- If you're online, the command runs exactly as if you typed it in game.
- If you're offline, it runs at world spawn with the permission level you'd have in game (from `ops.json`), so commands that need a player, like `/tp @s`, won't work.
- Linking never gives anyone more permissions than they have in Minecraft: a non-op can only run non-op commands.

Use `/unlink` in Discord or `/discord unlink` in Minecraft to remove the link. Links are stored in `config/discord-links.json`. Every command run from Discord is logged in the server console.

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
  "sendServerStartStop": true,
  "botToken": "",
  "guildId": ""
}
```

| Option | Description |
| --- | --- |
| `webhookUrl` | The Discord webhook URL. Messages aren't relayed while this is empty. |
| `serverName` | Name shown on Discord for server events (joins, deaths, …). |
| `serverAvatarUrl` | Avatar image for server events. Leave empty to use the webhook's own avatar. |
| `playerAvatarUrl` | Avatar for chat messages. `{uuid}` and `{name}` are replaced with the player's UUID and name. |
| `send*` | Turn each kind of message on or off. |
| `botToken` | Discord bot token for account linking and `/mc`. Leave empty to only use the webhook. |
| `guildId` | Your Discord server's ID. Slash commands are registered there and only accepted from there. |

Deaths and advancements follow the vanilla `showDeathMessages` and `announceAdvancements` game rules: if the game doesn't announce them, they're not sent to Discord either. Chat can't ping `@everyone`, `@here`, users or roles, and Discord markdown in names and messages is escaped.

## Building

You need JDK 25.

```sh
./gradlew build
```

The jar is written to `build/libs/`. Each push also builds the mod on GitHub Actions, and the jar can be downloaded from the run's artifacts.
