# Android TV Family Photo Screensaver (Immich + NAS)

## 1. Objective

Build a reliable Android TV screensaver app that displays family photos from an Immich server on Raspberry Pi 5 with NAS-backed storage.

The system must support large-scale libraries (20,000+ assets), long-running stability, offline tolerance, and TV-friendly navigation.

## 2. Scope

### In scope
- Android TV slideshow app and DreamService runtime.
- Immich-backed authentication and media retrieval.
- Album-based and people/pets-oriented playback modes.
- Local metadata and image caching.
- Sony Bravia compatibility.

### Out of scope
- Direct Google Photos API browsing as primary source.
- Undocumented Google endpoints.
- Public internet exposure of NAS shares.

## 3. System Architecture

### 3.1 Raspberry Pi 5
- Hosts Immich, PostgreSQL, Redis.
- Runs indexing and face/person processing.
- Serves API and thumbnails.
- Mounts NAS content as external library.

### 3.2 NAS
- Authoritative storage for originals.
- Mounted by Pi over NFS (preferred) or SMB (fallback).

### 3.3 Android TV app
- Uses Immich API key auth.
- Fetches album/person assets with pagination.
- Caches metadata and images.
- Renders slideshow in DreamService.

## 4. Platform Requirements

### 4.1 Backend host
- Raspberry Pi 5, 8GB RAM.
- 1TB+ SSD over USB3 for DB/cache/containers.
- Wired Ethernet.
- Active cooling.

### 4.2 OS
- Raspberry Pi OS 64-bit or Ubuntu Server 24.04 ARM64.
- Docker + Docker Compose.

### 4.3 Android app
- Min SDK 29.
- Kotlin.
- Coroutines.
- MVVM.
- Compose for settings/selection UI (Dream renderer may stay View-based).

## 5. Immich Requirements

- Latest stable pinned release in compose (do not float latest on production updates).
- Features required:
  - User/API key auth.
  - Albums and shared albums.
  - Person recognition.
  - External library support.
  - Thumbnail generation.
  - REST API access.

### 5.1 Pets requirement
- v1 definition:
  - Pets are provided via manual albums or tags/smart grouping.
- Future:
  - Upgrade to model-based pet recognition only if verified in deployed Immich version.

## 6. NAS Integration Requirements

### 6.1 Mounting
- Preferred protocol: NFS.
- Fallback: SMB.
- Mount path example: /mnt/photos
- Immich external library path example: /mnt/photos/family

### 6.2 Reliability mount options
- Use systemd automount with network-aware configuration.
- Include nofail and _netdev behavior.
- Ensure boot does not block if NAS is temporarily unavailable.

## 7. Android TV Functional Requirements

## 7.1 Authentication
- User enters Immich server URL and API key.
- API key stored in EncryptedSharedPreferences.
- App reconnects after reboot without manual login.

## 7.2 Source selection
- Select one or multiple albums.
- Select people-focused and pets-focused source groups.
- Enable shuffle mode.
- Persist selections locally.

## 7.3 Slideshow behavior
- Fullscreen images.
- Fade transitions.
- Optional Ken Burns pan/zoom.
- Preload next image.
- Randomized playback.
- Anti-repeat window (recent history) to avoid immediate repeats.
- Adjustable interval and transition duration.

Default values:
- Slide interval: 20s
- Fade transition: 1s

## 7.4 DreamService behavior
- Works as Android DreamService.
- Starts reliably as screensaver.
- Recovers after idle/wake cycles.
- Avoids TV focus lockups.

## 7.5 Offline tolerance
If Immich is unavailable:
- Continue slideshow from local image cache.
- Avoid blocking UI thread on network failures.
- Retry API with exponential backoff.

## 8. Performance and Stability Requirements

- Do not load full 20,000+ library into memory.
- Strict paginated API traversal.
- Bounded in-memory queue for candidate assets.
- Background metadata sync only.
- Preload small look-ahead buffer for smooth transitions.
- Stable operation for multi-day runtime without restart.

Suggested operational bounds:
- In-memory candidate queue: bounded (for example <=1000 ids).
- Recent-history anti-repeat ring: bounded (for example 100-300 ids).
- Disk image cache quota: configurable (for example 10-30 GB).

## 9. Data and Storage Requirements

### 9.1 Room database
Store:
- Album selections.
- Asset metadata cache.
- Slideshow history.
- Sync cursors/pagination state.
- Last-success and retry timestamps.

### 9.2 File cache
- Store fetched display images on disk.
- Keep metadata and file cache as separate concerns.
- Support cache cleanup by LRU/size.

## 10. API Requirements

Use Immich REST APIs for:
- API key validation/session test.
- Album list retrieval.
- Asset retrieval by album/person with pagination.
- Thumbnail and image retrieval.

Client behavior:
- Retry with bounded attempts.
- Request timeout handling.
- Exponential backoff on transient errors.
- Graceful fallback to cached content.

## 11. Security Requirements

- HTTPS strongly preferred (reverse proxy + TLS).
- Store API keys encrypted at rest on device.
- NAS must remain private to LAN.
- Do not expose SMB/NFS shares to internet.

## 12. Non-Goals

- Full Google Photos API mirroring.
- Continuous cloud API enumeration.
- Dependency on undocumented Google APIs.

## 13. Deliverables

### 13.1 Backend
- Docker compose configuration (pinned versions).
- Immich deployment guide.
- NAS mount guide.
- Backup and restore runbook.

### 13.2 Android TV app
- Kotlin Android project.
- DreamService implementation.
- Immich API client/service layer.
- Room database + DAO layer.
- Coil image pipeline and disk cache policy.
- Settings UI and album/person selector UI.
- Slideshow renderer.

## 14. Future Enhancements

- Weather overlay.
- Clock overlay.
- Multi-TV sync.
- Motion sensor triggers.
- Seasonal playlists.
- On-this-day memories.
- Voice integration.
- Plex/Jellyfin adapters.
