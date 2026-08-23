# Duelscape TCG

Build a deck from Old School RuneScape cards and battle inside RuneLite. Duelscape TCG combines a 400-card collection, a tavern-style deck workbench, local hot-seat matches, and encrypted friend duels over RuneLite Party.

## Highlights

| | Feature |
|---|---|
| **Build** | Search and filter the full catalog, inspect card rules, shape a mana curve, and save incomplete drafts. |
| **Start immediately** | Three complete starter decks remain playable without collection ownership while unchanged. |
| **Battle locally** | Use your selected deck against a starter deck in a two-player hot-seat match. |
| **Challenge friends** | Invite another member of your RuneLite Party and verify the shared security code before playing. |
| **Follow the battle** | Open a compact in-board log for turns, summons, attacks, damage, defeated units, and results. |
| **Play distinct strategies** | Shield, Lifesteal, Poisonous, Rush, Taunt, Stealth, Deploy, Deathrattle, temporary mana, and Nex's alternate victory condition are supported. |

## Quick Start

1. Use the **OSRS TCG** plugin to open card packs and begin building your collection.
2. Open **Duelscape TCG** from the RuneLite sidebar.
3. Open **Deck Builder** and create a custom 30-card deck from your collection, or select a built-in starter.
4. Press **Use Starter** or **Save and Use** when the deck reports that it is ready.
5. Choose **Local Battle** to play on one client, or invite a member of your RuneLite Party to a friend duel.

Opening packs is the normal path for expanding custom decks. The three unchanged starter decks remain available immediately if you want to learn the battle rules first.

Both players need the same current plugin version for a friend duel. Catalog, ruleset, and deck-commitment checks reject incompatible clients before play begins.

## Deck Workbench

The deck builder is organized as a card library and a deck ledger.

- Search names, rules, tags, factions, rarities, and abilities.
- Filter by card type, ownership, faction, and rarity.
- Sort by mana, name, rarity, or faction.
- Double-click a library card to add it.
- Select a ledger row to add, remove, or clear its copies.
- Review card count, unit and special totals, average mana, and the mana curve while editing.
- Save incomplete or invalid decks as drafts without making them active.

Deck status is shown consistently throughout the plugin:

| Status | Meaning |
|---|---|
| **Built-in starter - ready** | The original starter definition is ownership-exempt and can be played immediately. |
| **Ready to play** | The deck has exactly 30 legal, owned cards. |
| **Ownership check pending** | The deck structure is valid, but collection information has not loaded. |
| **Deck errors** | The card count, copy limits, catalog references, or ownership requirements need attention. |

Unsaved edits are marked with `*`. Switching decks or closing the workbench offers **Save Draft**, **Discard**, and **Cancel** instead of silently losing changes.

## Starter Decks

| Starter | Style |
|---|---|
| **Deathrattle Value** | Generates cards, Spirits, and hero pressure when units die. |
| **Rush Swarm** | Establishes tempo quickly with Rush units, direct damage, and low-cost support. |
| **Gielinor Bulwark** | Uses Shield, Taunt, Lifesteal, and boosts to control the battlefield. |

Built-in starters are protected templates. Use **Duplicate** to create an editable custom copy. A previously customized starter can be restored with **Reset**, but modified starters follow normal ownership rules.

## Core Rules

- Each deck contains exactly 30 cards.
- Standard cards allow up to two copies; Legendary cards allow one.
- Both players receive a five-card opening hand and may mulligan before play.
- The first player receives the normal draw when their first turn begins.
- Heroes begin with 20 health.
- Maximum mana increases to 10 over the course of the match.
- Each side can control up to seven units.
- Ready units can be clicked or dragged onto an enemy unit or hero to attack.
- Hands hold up to ten cards; failed draws from an empty deck cause increasing fatigue damage.

### Keywords

| Keyword | Effect |
|---|---|
| **Shield** | Prevents the next source of damage. |
| **Lifesteal** | Damage restores that much health to the controlling hero. |
| **Poisonous** | Any combat damage destroys the damaged unit. |
| **Rush** | The unit may attack enemy units on the turn it is played. |
| **Taunt** | Visible Taunt units must be attacked before other targets. |
| **Stealth** | The unit cannot be targeted until it attacks. |
| **Deploy** | Resolves an effect when the card is played. |
| **Deathrattle** | Resolves an effect when the unit dies. |

Nex is a special alternate win condition. If General Graardor, Commander Zilyana, Kree'arra, and K'ril Tsutsaroth are allied on your battlefield, successfully summoning Nex wins the match immediately.

## Local Battles

**Local Battle** uses the deck currently marked **IN USE** for Player 1. Before the match opens, choose one of the three starter decks for Player 2. The battle window changes seats between turns so the inactive player's hand is not displayed.

## Friend Duels

1. Join the same RuneLite Party as your opponent.
2. Ensure your selected deck reports **Ready to play** or **Built-in starter - ready**.
3. Choose an eligible party member and send an invitation.
4. Compare the short security code shown to both players.
5. Accept only when the codes match.

Friend-duel payloads are encrypted between participants and protected against replay. Encryption protects the Party traffic from observers; it does not hide game data from the opponent's client. Hand concealment is visual only: both clients reconstruct the deterministic match state, so fair play relies on an honest, unmodified opponent client. Setup `SNAPSHOT` messages exchange committed deck and seed data; during live play, revision numbers, state hashes, acknowledgements, and bounded retransmission keep both clients in lockstep. Live state recovery is not implemented, so a mismatch or exhausted retry budget aborts the match. A missing or unusable deck is never silently replaced.

## Collection Ownership

Custom-deck ownership is requested locally through the OSRS TCG plugin-message API. The collection integration distinguishes an unavailable collection from a known empty collection.

Built-in starters do not require ownership while they exactly match their original definitions. Duplicating or modifying a starter creates a normal custom deck.

Deck profiles are stored through RuneLite's profile-aware configuration system. Switching RuneScape profiles loads the corresponding saved decks.

## Network And Storage

- The plugin does not use an independent game server or account system.
- Friend-duel messages travel through RuneLite Party services.
- Card ownership is exchanged locally through RuneLite plugin messages.
- Card artwork may be downloaded from URLs included in the bundled catalog.
- Artwork is cached under RuneLite's `OSRS-TCG/images-v2` directory and shared with compatible legacy TCG plugins. OSRS TCG v1 uses its own separate image cache.
- Deck lists are not uploaded to a separate service.

## Troubleshooting

**My custom deck says ownership is pending.**

Ensure a compatible OSRS TCG collection plugin is active, then use **Refresh Collection** in the sidebar or deck workbench.

**I cannot send or accept a friend-duel invitation.**

Both players must be logged into the same RuneLite Party. Your selected deck must be ready, and both clients must use compatible catalog and ruleset versions.

**Editing a starter made cards unavailable.**

Use **Reset** to restore the built-in template or **Duplicate** before editing. Only the exact original starter receives the ownership exemption.

**I found a problem.**

Open an issue at [github.com/randytkrx/osrs-tcg-battles/issues](https://github.com/randytkrx/osrs-tcg-battles/issues) with the steps needed to reproduce it.

## Acknowledgements

Duelscape TCG is an independent community plugin. Old School RuneScape and RuneScape are trademarks of Jagex Ltd. Card collection integration and image-cache compatibility build on the work of the OSRS TCG community; see [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) for attribution.
