English · [繁體中文](../zh-TW/README.md) · [简体中文](../zh-CN/README.md)

# ChunkLand documentation

Pick the document that matches what you are doing. Nothing here assumes you
have read the others.

| Document | Read it when |
| --- | --- |
| [User guide](user-guide.md) | You are a player claiming land, trusting someone, or wondering why an action was refused |
| [Server guide](server-guide.md) | You run the server and need to install, configure, back up, or recover |
| [Developer guide](developer-guide.md) | You are writing a plugin that reads ChunkLand state or listens to its events |
| [Command reference](reference/commands.md) | You need the exact syntax of a `/land` or `/chunkland` subcommand |
| [Configuration reference](reference/configuration.md) | You need to know what a `config.yml` key does and what it accepts |
| [Permission reference](reference/permissions.md) | You need to know which node to grant a player |
| [API reference](reference/api.md) | You are calling `ChunkLandApi` or registering an event listener |
| [Limitations](limitations.md) | You are about to rely on something that might not work |

## Support baseline

ChunkLand runs on **Folia 26.2** with **Java 25** and requires **AceLib 1.3.0**
on the server. Paper is not a supported runtime. The `api-version: '26.1.2'` in
`plugin.yml` and the paper-api 26.1.2 compile dependency are build markers; they
do not mean Paper 26.1.2 is supported.

## Three things to know before you read further

Entry is allowed by default, so strangers can walk into a claim until you either
set `subject-defaults.global.ENTRY: DENY` or ban them.

The eleven land rules — PVP, explosions, fire, mob griefing, fluid flow,
pistons, hopper transfer, mob spawning — have no interface to change them. They
run on built-in defaults with everything denied except passive mob spawning.

Every permission node defaults to `op`. Regular players cannot claim land or
trust members until an operator grants the node explicitly.

## Language versions

Every document exists in English, Traditional Chinese and Simplified Chinese.
The English copy is written first and the translations are maintained alongside
it, so a change to one is expected in the other two. If you find a page where
the three disagree, that is a bug worth reporting.

Repository root: <https://github.com/smile-minecraft/ChunkLand>