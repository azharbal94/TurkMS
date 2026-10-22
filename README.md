# TurkMS

A multi-source [CloudStream 3](https://github.com/recloudstream/cloudstream)
plugin for Turkish movies and TV series, backed by **TMDB v3**.

TurkMS does not host content. It reads movie/show metadata from TMDB, collects
playback links from third-party sites, and hands them to the player.

## Features

- **English UI**: catalogs, settings, and error states are in English.
- **Turkish-only catalogs**: six TMDB rows and a **Ddizi Latest** row on the
  home page — Turkish Movies, Turkish Series, New Turkish Movies, New Turkish
  Series, Top Rated Turkish Movies, Top Rated Turkish Series, and Ddizi Latest.
  The TMDB rows use `with_original_language=tr`, so English, Korean, Japanese
  and other productions are not mixed in.
- **More than 20 per row**: each TMDB row opens with **60 titles** (three TMDB
  pages per app page) and supports load-more up to six app pages. The Ddizi row
  shows its latest Turkish series (currently about 28 after foreign titles are
  filtered out). Other native site providers open with 30–90 cards and keep
  paginating through the site archive.
- **Search**: TMDB first; if it returns few results, the scraped sites are
  searched as well.
- **Detail pages**: poster, backdrop, overview, year, runtime, score, genres,
  cast, trailers, and recommendations.
- **Episode lists**: seasons and episodes from TMDB, with names, numbers, and
  stills.
- **Multi-source playback**: links from several sites are merged, de-duplicated,
  and sorted by quality. Series come from four sites; movies come from YouTube.
  The FirePlayer used by the series sites is read directly, including its
  alternative servers, so one episode can offer several qualities and mirrors.
- **1080p preference**: the quality chosen in settings (1080p by default) is
  moved to the top. Streams without a label are shown as `Unknown` instead of
  a fake resolution.
- **Arabic subtitles (optional)**: matched by IMDb id (movie, or exact season
  and episode) and offered in the player's subtitle menu. Providers include
  **SubtitleCat** (keyless, direct SRT), **Wyzie Subs** (free key,
  `source=all`), the keyless **OpenSubtitles v3 Stremio addon**, plus optional
  **SubDL** and **SubSource** keys. ZIP/GZIP archives are unpacked and converted
  to UTF-8 SRT/VTT locally, so a packed subtitle still plays. Every result is
  filtered by title/year and exact SxxExx before it is shown.
- **Fast navigation**: site searches, detail pages, and TMDB lookups are cached
  for ten minutes, so switching episodes does not repeat the same requests.
  Subtitle providers are queried in parallel with a 16-second cap.
- **Toggleable sources**: disable any site from settings if it breaks.
- **Separate providers**: besides the `TurkMS` aggregator, each site is
  registered as its own provider with a **Latest & Archive** catalog
  (`CanlıDizi14`, `Ddizi`, `Diziizle`, `Trdiziizle`), plus a `YouTube`
  provider that browses TMDB movies and plays them from YouTube.

## Sources

Directly scraped sites:

| Key | Site | Content | Player |
| --- | --- | --- | --- |
| `canlidizi14` | canlidizi14.net | TV series | YouTube / hlszone / JW Player |
| `ddizi` | ddizi.tel | TV series | JW Player (Ciner MP4/HLS) |
| `diziizlenow` | diziizle.you | TV series | YouTube |
| `trdiziizle` | trdiziizle.tv | TV series | JW Player / Twitter HLS |
| `youtube` | youtube.com | Movies | CloudStream YouTube extractor |

Catalog pagination:

- `canlidizi14` reads `/dizi-arsivi/page/N` (three pages per app page, 30
  cards).
- `ddizi` reads `/yeni-eklenenler8`, removes the foreign shows listed on
  `/yabanci-dizi-izle`, and shows the remaining Turkish series (no pagination).
- `diziizlenow` reads `/diziler/sayfa-N/` (three pages per app page, 90 cards,
  38 archive pages).
- `trdiziizle` tries the shared `/dizi-arsivi/page/N` theme paths and falls back
  to its series section; some networks receive 403 from the site.
- TMDB rows fetch three TMDB pages per app page, so a row opens with 60 titles
  and loads up to 360.

Every collected link is also passed through CloudStream's built-in extractor
library (`loadExtractor`), which understands dozens of hosts such as YouTube,
Vimeo, Dailymotion, Doodstream, Filemoon, and Streamtape. So "50+ providers"
means 5 scraped sources plus CloudStream's host support — not 50 scraped sites.

### About movies

The four scraped sites only publish **TV series** (canlidizi14's "movies"
section is made of digital series split into episodes). Movie playback
therefore uses the **YouTube** source:

1. The movie's Turkish title is read from TMDB.
2. YouTube is searched for `"<title> full film türkçe izle"`.
3. Videos longer than 20 minutes with a matching title are selected.
4. The `watch?v=...` URL is passed to CloudStream's YouTube extractor.

YouTube may refuse some videos due to signature or age restrictions; in that
case the movie page still opens but the playback list stays empty. That is
more honest than silently returning a wrong stream.

## Installation

### As a repository (recommended)

Repository URL:

```
https://raw.githubusercontent.com/azharbal94/TurkMS/builds/repo.json
```

One-click install:

```
cloudstreamrepo://raw.githubusercontent.com/azharbal94/TurkMS/builds/repo.json
```

In CloudStream: **Settings → Extensions → Add repository**, paste the URL,
refresh, then install **TurkMS**.

The `builds` branch is generated by GitHub Actions and contains `TurkMS.cs3`,
`plugins.json`, and `repo.json`.

> The repository must be **public**. Raw URLs from a private repository return
> 404 for everyone except you.

### Manual installation

Download `TurkMS.cs3` from the `builds` branch and open it with
**Settings → Extensions → Install local extension**.

## Settings

Open **Extensions → TurkMS → Settings**:

- **TMDB API key (v3)**: leave empty to use the built-in demo key. Get a
  personal key at <https://www.themoviedb.org/settings/api>.
- **Sources**: toggle each of the four series sites and the YouTube movie
  search.
- **Arabic subtitles (optional)**: on by default. Turn it off to skip the
  subtitle lookup entirely.
- **Wyzie Subs API key**: optional. Get a free key at
  <https://store.wyzie.io/redeem> (1,000 requests/day, no payment). Searches use
  `source=all` to aggregate Wyzie's providers. The key is stored only on the
  device.
- **SubDL API key**: optional. Get a free key at
  <https://subdl.com/panel/api>. Enables SubDL Arabic subtitles. SubDL returns
  ZIP archives for some releases, which the plugin unpacks locally.
- **SubSource API key**: optional. Copy the API key from your
  <https://subsource.net> account. Enables SubSource Arabic subtitles. Downloads
  are ZIP files and are unpacked locally.
- **Preferred quality**: 1080p (default), 720p, or 4K.
- **Request timeout**: 20 seconds by default.

**SubtitleCat is keyless** and is always queried. The OpenSubtitles v3 Stremio
fallback is also keyless. Without any keys, those two sources still provide
Arabic subtitles for many titles.

Settings are stored in the `turkms` `SharedPreferences` file.

## Build

Requirements: JDK 17, Android SDK (platform 35, build-tools 35.0.0).

```bash
./gradlew make makePluginsJson
```

Outputs:

- `TurkMS/build/TurkMS.cs3`
- `build/plugins.json`

On Windows:

```powershell
$env:JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-17.0.20.101-hotspot"
$env:ANDROID_HOME="$env:LOCALAPPDATA\Android\Sdk"
.\gradlew.bat make makePluginsJson
```

> Note: `com.lagradost:cloudstream3:pre-release` is compiled with Kotlin
> metadata 2.4.0, so this project uses Kotlin **2.4.0**. The official template
> still uses 2.1.0, which fails with
> `binary version of its metadata is 2.4.0`.

## Publishing

The workflow builds on every push to `main` and force-pushes the artifacts to
the `builds` branch.

1. Create a **public** GitHub repository.
2. Push the project:

   ```bash
   git remote add origin https://github.com/<USER>/TurkMS.git
   git push -u origin main
   ```

3. Go to **Settings → Actions → General → Workflow permissions** and select
   **Read and write permissions**. Without this the workflow cannot push
   `builds`.
4. Wait for the `Build` workflow under the **Actions** tab.
5. Verify that `builds` contains `TurkMS.cs3`, `plugins.json`, and `repo.json`.

Only `*.md` and `LICENSE` changes skip the build; run
**Actions → Build → Run workflow** manually if needed.

### URLs to share

Repository:

```
https://raw.githubusercontent.com/<USER>/TurkMS/builds/repo.json
```

One-click:

```
cloudstreamrepo://raw.githubusercontent.com/<USER>/TurkMS/builds/repo.json
```

CNCVerse Bridge accepts the same `repo.json`. `plugins.json` already contains
the `.cs3` download URL, file size, and hash, so users get updates
automatically.

### Publishing without Actions

```bash
./gradlew make makePluginsJson

git checkout --orphan builds
git rm -rf .
cp TurkMS/build/TurkMS.cs3 .
cp build/plugins.json .
# repo.json: same structure as above, pluginLists pointing at your repo
git add -A
git commit -m "Manual build"
git push -f origin builds
```

## Troubleshooting

- **Repository URL returns 404**: the repo is private, the `builds` branch does
  not exist yet, or the workflow failed. Check the **Actions** tab.
- **A site provider is missing**: update TurkMS from the extension list and
  restart CloudStream. Providers are grouped by content language, so the site
  providers appear under Turkish.
- **No streams for a series**: the site may be down, geo-blocked, or changed
  its markup. Disable that source and try another.
- **No streams for a movie**: YouTube extraction failed for that video. Try
  again later; the plugin never substitutes a wrong link.
- **No Arabic subtitles for a title**: subtitles are matched by IMDb id or
  title, and TV results must have the exact season/episode marker. A title that
  TMDB cannot map to IMDb, or one that SubtitleCat/OpenSubtitles does not carry
  yet, may have no Arabic option. Add Wyzie/SubDL/SubSource keys for wider
  coverage; the video itself still plays without subtitles.
- **A packed subtitle does not load**: SubDL and SubSource return ZIP files, and
  some OpenSubtitles entries are GZIP/ASS. The plugin extracts and converts
  them to UTF-8 and serves them through a short-lived loopback HTTP server. If
  the device blocks local loopback traffic (rare), only direct SRT/VTT sources
  will appear.
- **SubDL/SubSource key rejected**: the APIs return an error for an invalid,
  expired, or rate-limited key. Check the key in settings and the provider's
  daily limit. SubtitleCat and the keyless OpenSubtitles fallback keep working.
- **Wyzie key rejected**: the search API returns 403 for an invalid or paused
  key and 429 when the free daily limit is used up. Check the key or wait for
  the UTC reset. The keyless fallback keeps working.
- **TMDB errors**: the demo key is rate-limited. Add a personal key in
  settings.
- **`trdiziizle.tv` returns 403**: the site blocks some networks/regions. It is
  not a plugin bug; the source fails gracefully.

## Project layout

```
TurkMS/
├── build.gradle.kts              # Root Gradle configuration
├── TurkMS/
│   ├── build.gradle.kts          # Plugin metadata
│   └── src/main/kotlin/com/turkms/
│       ├── TurkMSPlugin.kt       # Entry point, provider registration
│       ├── providers/            # TurkMS + 4 site providers
│       ├── sources/              # Site scrapers
│       ├── Tmdb.kt               # TMDB v3 client
│       ├── Subtitles.kt          # SubtitleCat/Wyzie/SubDL/SubSource + matching
│       ├── SubtitleResolver.kt   # ZIP/GZIP/ASS → SRT + loopback HTTP proxy
│       ├── FirePlayer.kt         # FirePlayer stream + alternate-server extractor
│       ├── Cache.kt              # Short-lived search/detail cache
│       ├── Mapping.kt            # Site models → CloudStream models
│       ├── Site.kt               # Source abstraction + link helpers
│       ├── Http.kt               # HTTP/HTML/header helpers
│       └── Settings*.kt          # Preferences and settings UI
└── .github/workflows/build.yml   # .cs3 + plugins.json + repo.json
```

## Legal notice

This plugin does not host or distribute any video files. All links belong to
third-party sites. Usage is at your own risk; follow the terms of service of
each content provider and the laws of your country.
