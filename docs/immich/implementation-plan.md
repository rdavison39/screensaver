# Immich Migration Implementation Plan

## 1. Strategy

Deliver in three phases to reduce risk:
- Phase 1: baseline stable slideshow with Immich albums.
- Phase 2: scale, reliability, and people/pets workflows.
- Phase 3: polish, diagnostics, and long-run operations.

## 2. Backend Plan (Pi + NAS)

## 2.1 Infrastructure setup
1. Install OS updates and Docker/Compose.
2. Attach SSD and configure persistent filesystem.
3. Configure static DHCP reservation for Pi.
4. Configure NTP/time sync.

Acceptance:
- Pi reboots cleanly and Docker services auto-start.

## 2.2 NAS mount and reliability
1. Configure NFS mount at /mnt/photos (SMB fallback).
2. Add systemd automount and network-safe options.
3. Verify mount survives reboot and temporary NAS outage.

Acceptance:
- /mnt/photos/family readable after reboot.
- Boot does not hang when NAS is offline.

## 2.3 Immich deployment
1. Create docker-compose with pinned image versions.
2. Configure Postgres and Redis persistent volumes.
3. Configure external library path to /mnt/photos/family.
4. Run initial Immich indexing.

Acceptance:
- Immich UI reachable on LAN.
- Albums/assets visible from external library.

## 2.4 Security hardening
1. Put Caddy or Nginx reverse proxy in front of Immich.
2. Enable TLS on LAN cert or trusted local CA.
3. Restrict exposure to LAN.

Acceptance:
- Android TV can connect via HTTPS endpoint.

## 2.5 Backup and operations
1. Nightly Postgres dumps.
2. Periodic validation restore process.
3. Backup compose/env/config files.
4. Define upgrade runbook with rollback.

Acceptance:
- Documented restore drill succeeds.

## 3. Android App Plan

## 3.1 Module and package layout
- app/src/main/java/.../data/api
- app/src/main/java/.../data/db
- app/src/main/java/.../data/repository
- app/src/main/java/.../domain
- app/src/main/java/.../ui/settings
- app/src/main/java/.../ui/albums
- app/src/main/java/.../dream

## 3.2 Data layer
1. Create ImmichApiService interface + DTOs.
2. Implement client with OkHttp/Retrofit or current OkHttp style.
3. Add timeout/retry policy wrappers.
4. Add Room entities/DAO for:
   - asset metadata
   - album mappings
   - selection state
   - playback history
   - sync cursors

Acceptance:
- End-to-end asset page fetch stored in DB.

## 3.3 Auth and settings
1. Add settings screen for:
   - Immich base URL
   - API key
   - slideshow interval
   - transition duration
   - shuffle toggle
2. Save secrets in EncryptedSharedPreferences.
3. Add connectivity/auth test button.

Acceptance:
- Settings persist across app restart.
- Invalid key handling shown clearly.

## 3.4 Source selection UX
1. Build album/person selector.
2. Support multi-select.
3. Define pets in v1 as selected albums/tags.
4. Persist source selection in Room.

Acceptance:
- Selected sources restored after reboot.

## 3.5 Slideshow engine
1. Build playback queue generator from Room + paged sync.
2. Implement anti-repeat ring buffer.
3. Add prefetch pipeline (next image).
4. Add fade transition and optional Ken Burns.
5. Ensure render path never blocks on network.

Acceptance:
- Smooth playback for 2+ hours with no stutter loops.

## 3.6 DreamService integration
1. Keep DreamService lifecycle robust.
2. Start/stop background workers on dream visibility events.
3. Recover state after wake/sleep.
4. Keep TV focus predictable.

Acceptance:
- Repeated dream cycles without lockups.

## 3.7 Offline behavior
1. On API failures, serve cached images only.
2. Backoff retry scheduler for sync.
3. Resume sync when backend returns.

Acceptance:
- Slideshow continues during backend outage.

## 3.8 Performance guardrails
1. Enforce max queue sizes.
2. Enforce disk cache quotas.
3. Add periodic cache cleanup worker.
4. Add lightweight diagnostics view:
   - selected source count
   - indexed assets count
   - queue size
   - cache size
   - last sync/error

Acceptance:
- No unbounded memory growth in long runs.

## 4. API Contract Checklist (Immich)

Required capabilities in app client:
1. Validate API key/session.
2. Fetch albums with pagination.
3. Fetch album assets with pagination.
4. Fetch person buckets and person assets (if enabled).
5. Build thumbnail/original URLs.
6. Handle 401/403/429/5xx consistently.

## 5. Testing Plan

## 5.1 Functional
1. Auth success/failure.
2. Album/person selection persistence.
3. Shuffle and anti-repeat behavior.
4. Interval and transition settings.

## 5.2 Reliability
1. Run slideshow overnight.
2. Simulate backend outage and recovery.
3. Simulate NAS unavailable during startup.
4. Reboot TV and confirm auto-recovery.

## 5.3 Performance
1. Validate paging on 20k+ assets.
2. Confirm memory stays bounded.
3. Validate cache cleanup behavior.

## 5.4 Device compatibility
1. Android TV emulator (primary dev target).
2. Sony Bravia final verification.

## 6. Milestones

## Milestone A: Backend ready
- Immich + NAS + HTTPS + backup runbook operational.

## Milestone B: App baseline
- Auth, album selection, slideshow from Immich album, DreamService stable.

## Milestone C: Scale ready
- Pagination, Room caching, anti-repeat, offline playback, long-run stability.

## Milestone D: Production hardening
- Diagnostics, tuning, reliability checks, final Sony verification.

## 7. Risks and Mitigations

1. Immich API changes:
- Pin server version and track release notes.

2. NAS mount instability:
- Use automount and resilient options.

3. TV memory pressure:
- Strict queue/cache limits and low-overhead render path.

4. Person/pet metadata variance:
- Keep pets as manual/tag albums in v1.

## 8. Immediate Next Actions

1. Add docs and lock requirements (done).
2. Scaffold app data layer for Immich APIs.
3. Add settings screen for Immich URL/API key.
4. Implement first paginated album asset sync into Room.
5. Hook slideshow playback to Room-backed queue.
