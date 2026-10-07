# Backlog

Planned work not yet scheduled into a spec. Newest first.

Done: more news sources (DDay, Internazionale, il manifesto, Il Foglio) — 2026-10-06, commit f6df813.

## Per-story source links in show notes

**Requested:** 2026-10-06

Today the episode description ends with a single flat "Fonti:" / "Sources:" list of every article used (`ScriptWriter.showNotes`). Group it by story instead, so each segment's headline is followed by its own source links, in the episode order:

```
Fonti:
1. <segment headline>
   - <article title> — <url>
   - <article title> — <url>
2. <segment headline>
   - …
```

- Data is already available: `episode.outline.segments[*].headline` and `itemIds`, plus the items' `title` and `url`.
- Applies to the podcast feed `<description>`, the admin episode page and `/api/episodes/{id}`, since all three show `episode.description`.
- Reddit items (see the Reddit connector spec) should list both the original article link and the Reddit thread link under their story.
