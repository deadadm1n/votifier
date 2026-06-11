<p align="center">
  <img src="modded-votifier-logo.png" alt="Modded Votifier" width="150">
</p>

<h1 align="center">Modded Votifier</h1>

<p align="center">
  A server-side Minecraft mod that receives server-list votes, stores player vote balances, and lets players spend votes in a configurable vote shop.
</p>

---

## Features

- Votifier v1 support using the generated RSA public/private key pair
- NuVotifier v2 support using an HMAC token stored under `nuVotifierV2`
- Vote balances saved per player
- Pending reward commands for offline players
- Configurable vote shop with command rewards
- Vote history written to `vote_history.jsonl`
- Server-side only

## Installation

1. Drop the NeoForge jar into your server `mods` folder.
2. Start the server once to generate `config/votifier/`.
3. For Votifier v1 sites, paste `config/votifier/public.pem` into the server list website.
4. For NuVotifier v2 sites, paste `nuVotifierV2.token` from `config/votifier/config.json`.
5. Forward the configured TCP port, usually `8192`, to the Minecraft server.

## Configuration

Files are stored in `config/votifier/`.

### `config.json`

```json
{
  "host": "0.0.0.0",
  "port": 8192,
  "votifierV1": {
    "enabled": true
  },
  "nuVotifierV2": {
    "enabled": true,
    "token": "auto-generated-token"
  },
  "votePointsPerVote": 1,
  "voteCommands": [
    "say {player} voted and earned {points} vote point(s)!"
  ],
  "apiVoteSources": [
    {
      "enabled": false,
      "id": "acserverlist",
      "baseUrl": "https://deutsche-arkserver.de",
      "apiKey": "",
      "serverId": "",
      "pollIntervalSeconds": 60,
      "unclaimedVotesUrl": "{baseUrl}/api/v2/{apiKey}/server/{serverId}/votes/unclaimed",
      "unclaimedVotesMethod": "GET",
      "unclaimedVotesBody": "",
      "batchClaimUrl": "{baseUrl}/api/v2/{apiKey}/server/{serverId}/votes/batch",
      "batchClaimMethod": "PATCH",
      "batchClaimBody": "{\"votes\":{voteUpdatesJsonArray}}",
      "headers": {},
      "votesPath": "data",
      "voteIdPath": "id",
      "usernamePath": "username",
      "timestampPath": "timestamp",
      "addressPath": "address"
    }
  ],
  "voteShop": {
    "enabled": true,
    "items": [
      {
        "id": "vip",
        "name": "VIP Rank",
        "description": "Example rank reward.",
        "cost": 25,
        "commands": [
          "ftbranks add {player} vip",
          "ftbranks reload"
        ]
      }
    ]
  }
}
```

Old configs with a top-level `token` and `commands` are migrated automatically.

### API Vote Sources

`apiVoteSources` lets the mod pull votes from websites that expose HTTP APIs instead of sending Votifier packets.

The poller flow is:

1. Request unclaimed votes from `unclaimedVotesUrl`.
2. Read all votes from `votesPath`.
3. Extract each vote ID and username.
4. Send one batch claim request to `batchClaimUrl`.
5. If the claim request succeeds, store every vote in `player_votes.json`.

Template values:

- `{baseUrl}`
- `{apiKey}`
- `{serverId}`
- `{voteIdsJsonArray}` - `["1","2"]`
- `{voteIdsCsv}` - `1,2`
- `{voteUpdatesJsonArray}` - `[{"id":"1","claimed":true},{"id":"2","claimed":true}]`

JSON paths are dot-separated, for example `data.votes`.

### Placeholders

Vote commands support:

- `{player}`
- `{points}`

Shop item commands support:

- `{player}`
- `{item}`
- `{item_name}`
- `{cost}`

### Data Files

| File | Description |
|---|---|
| `config.json` | Listener, protocol, reward, and shop settings |
| `public.pem` | Votifier v1 public key for server list websites |
| `private.pem` | Votifier v1 private key used by the server |
| `player_votes.json` | Player balances, lifetime vote counts, and pending reward commands |
| `vote_history.jsonl` | Append-only vote history, one JSON vote per line |

## Commands

Player commands:

- `/vote` - show help
- `/vote balance` - show your vote balance and lifetime votes
- `/vote shop` - list shop items
- `/vote buy <item>` - spend votes on a shop item
- `/vote top` - show the highest stored balances

Admin commands, usable by console or opped players:

- `/vote admin add <player> <amount>`
- `/vote admin set <player> <amount>`

## How Votes Are Processed

When a vote arrives, the plugin:

1. Validates the v1 RSA payload or v2 HMAC signature.
2. Adds `votePointsPerVote` to the player's balance.
3. Adds the vote to `vote_history.jsonl`.
4. Stores pending reward commands for that player.
5. Runs pending vote commands immediately if the player is online, or when they next join.

## Building

```bash
./gradlew :neoforge:build
```

The NeoForge jar is written to `neoforge/build/libs/`.

## License

This project is licensed under the MIT License. See `LICENSE.txt` for details.
