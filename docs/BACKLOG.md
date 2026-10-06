# Backlog

Planned work not yet scheduled into a spec. Newest first.

## More news sources: DDay, Internazionale, il manifesto, Il Foglio

**Requested:** 2026-10-06

Feed check from the dev machine (2026-10-06), to verify again when doing this:

| Site | Feed | Status | Likely work |
|---|---|---|---|
| DDay (dday.it, tech) | `https://www.dday.it/rss` | 200, Atom, ~50 entries | add as `rss` source; check full-text extraction |
| Internazionale | `https://www.internazionale.it/sitemaps/rss.xml` | 200, XML (single-line, item count to verify) | add as `rss` source; articles may be paywalled — check extraction, possibly a site extractor |
| il manifesto | `https://ilmanifesto.it/feed` | 200, RSS, ~50 items | add as `rss` source; paywall likely — check what text extraction gets |
| Il Foglio | none found (`/rss.jsp` 410, `/feed` 404) | no public feed | needs a `site:ilfoglio` scraper connector, or a section feed if one exists |

For each: add the source to the right show, use **Test** to check items and extracted text, add a `ContentExtractor` when the generic one picks the wrong block.

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
