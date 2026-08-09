# OSRS TCG Battles

OSRS TCG Battles turns collected Old School RuneScape cards into playable deck battles inside RuneLite. Build a 30-card deck, challenge a friend through a RuneLite Party, or play a local hot-seat match on a responsive tavern-style board.

## Features

- A curated 400-card catalog compatible with the OSRS TCG collection.
- Three ready-to-play starter decks that do not require an existing collection.
- Custom deck building with ownership and copy-limit validation.
- Local hot-seat matches and synchronized RuneLite Party duels.
- Five-card opening hands, mulligans, mana progression, hero combat, and a seven-unit battlefield.
- Shield, Lifesteal, Poisonous, Rush, Taunt, Stealth, Deploy, and Deathrattle effects.
- An alternate Nex victory condition built around the four God Wars Dungeon generals.
- Drag-to-play cards, target prompts, card art, tooltips, and match result overlays.

## Getting Started

1. Open **OSRS TCG Battles** from the RuneLite sidebar.
2. Select one of the built-in starter decks or open the deck builder to create your own.
3. Choose **Local Battle** for a hot-seat game on one client.
4. To challenge a friend, join the same RuneLite Party, select a valid deck, and send an invitation from the Battles panel.
5. Compare the displayed security code before both players confirm the duel.

Both players need the same current version of OSRS TCG Battles for a synchronized friend duel. Ruleset and catalog checks reject incompatible clients before a match starts.

## Decks And Ownership

Custom decks contain exactly 30 cards and follow rarity-based copy limits. Card ownership is read through the OSRS TCG plugin-message API when a compatible collection plugin is active.

The built-in Deathrattle Value, Rush Swarm, and Gielinor Bulwark starter decks can always be played in their original form. Editing a starter turns it into a custom deck, so normal ownership checks apply.

Deck profiles are stored through RuneLite's profile-aware configuration system. The plugin does not upload deck lists to a separate service.

## Friend Duels

Friend invitations and match actions use RuneLite Party messaging. Duel payloads are encrypted between the two participants, protected against replay, and verified with a short code shown to both players. Public state hashes and recovery snapshots keep both clients synchronized without revealing the opponent's hand.

## Network And Storage

- No independent game server or account system is used.
- Friend-duel traffic travels through RuneLite Party services.
- Card ownership is requested locally through RuneLite plugin messages.
- Card artwork may be downloaded from the image URLs contained in the bundled catalog.
- Downloaded artwork is cached under RuneLite's `OSRS-TCG/images-v2` directory and shared with compatible TCG plugins to avoid duplicate downloads.

## Acknowledgements

OSRS TCG Battles is an independent community plugin. Old School RuneScape and RuneScape are trademarks of Jagex Ltd. Card collection integration and image-cache compatibility build on the work of the OSRS TCG community; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for attribution.
