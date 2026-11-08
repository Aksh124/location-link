# Location Link

Client-side Fabric mod for Minecraft 26.2 that shares your location with one friend over a
direct, encrypted peer-to-peer connection. Your positions never go through the Minecraft server,
and the server doesn't need any mods. Both players need Location Link and Fabric API.

## Usage

| Command | Effect |
| --- | --- |
| `/link invite <player>` | Send an invite by `/msg`. Their mod shows an **[Accept]** button |
| `/link code` | Show your code to send some other way (Discord, say) |
| `/link connect <code>` | Connect with a friend's code. If they don't have yours yet, it shows yours to send back |
| `/link` | Connection status and your public address |
| `/link stop` | Stop sharing. Your friend is told straight away |
| `/link hud` | Turn the HUD line on or off |

Once linked, the top of the screen shows your friend's coordinates, health, distance and an arrow
pointing to them. If they're in the Nether, it also shows where that is in the Overworld. If
they're on a different server, it says so. With
[Xaero's Minimap](https://modrinth.com/mod/xaeros-minimap) installed, they also get a live
waypoint. It's marked temporary, so Xaero doesn't save it.

Leaving the server ends the link.

## How it works

1. **Your public address.** The mod opens a UDP socket and asks a STUN server (Google's, then
   Cloudflare's) what address and port it appears to come from. Only that request goes to
   them, and it holds nothing about Minecraft.
2. **Codes.** Your code holds your name, a fresh X25519 public key, and the addresses you can be
   reached on: the public one from STUN plus your local network ones. About 85 characters, so it
   fits in a `/msg`.
3. **Hole punching.** Once each side has the other's code, both send small packets to all of the
   other's addresses. Each router sees its own player sending out and lets the replies back in,
   so within a moment packets get through both ways with no server in between.
4. **Encryption.** The two keys give a shared secret, and every packet is encrypted and signed
   with AES-256-GCM. Packets that don't decrypt, or that repeat or come before the last one, are
   dropped, so nobody else can read or fake your positions.

Positions go out four times a second, and a keepalive goes every second, so the connection stays
open. If packets stop, the link shows as lost and keeps trying.

## Privacy

- **Your code holds your IP address.** Only share it with people you trust. `/link invite`
  sends it by `/msg`, so the server, and any plugin that logs messages, can see it.
- **Each link gets new keys**, and nothing is saved between links.

## When it can't connect

Hole punching works on most home connections. If both players are behind strict NATs, which is
common on mobile data and with some ISPs, packets may never get through. The mod gives up after
45 seconds. The fix is to forward a UDP port on one player's router and set it in
`config/locationlink.properties`:

```properties
port=25570
stun=stun.l.google.com:19302,stun.cloudflare.com:3478
```

Players on the same network or PC connect over their local addresses. Leaving `stun` empty
skips STUN entirely.

## Building

Requires JDK 25.

```bash
./gradlew build
```

`./gradlew test` runs two links against each other on this computer. It checks codes, STUN
parsing, positions in both directions, the goodbye, and that tampered, replayed and outsider
packets are all rejected. Add `-PliveStun` to also ask the real STUN servers for your address.

`./gradlew runClientGameTest` runs the mod in the game against a stand-in friend. Invites and
replies arrive as real chat packets. It checks the [Accept] button, the automatic reply, the
HUD line and arrow, the Xaero waypoint, Nether coordinates, another server, and both ways of
ending the link.
