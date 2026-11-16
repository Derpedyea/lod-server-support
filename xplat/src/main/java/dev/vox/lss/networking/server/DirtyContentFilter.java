package dev.vox.lss.networking.server;

import dev.vox.lss.common.LSSLogger;
import dev.vox.lss.common.PositionUtil;
import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.HashMap;
import java.util.Map;

/**
 * Gates dirty-column marking on actual LOD-visible content change.
 *
 * <p>Vanilla re-saves loaded chunks on a ~10s cooldown for metadata-only changes
 * (inhabitedTime ticks every tick a player is in range), so a save-hook-driven dirty
 * tracker re-marks every loaded chunk near a player forever. Each mark triggers a
 * broadcast, a client re-request, and a full column re-send of identical bytes —
 * a measured ~2-5 columns/s/player of pure waste. This filter hashes exactly what LSS
 * serves (the serialized sections + light wire bytes) and lets a save mark dirty only
 * when that content actually changed.
 *
 * <p>Fail-open: serialization errors fall back to "changed" — a spurious re-send is
 * harmless, a missed update is not. Paper needs no twin: its dirty detection is
 * Bukkit-event-driven (block changes), not save-driven.
 *
 * <p>Thread contract (issue #69 retarget): callers arrive from the
 * {@code SerializableChunkData.copyOf} hook, which vanilla runs on the main server thread
 * but chunk-system overhaul mods legitimately run elsewhere (C2ME's rewritten system
 * calls copyOf from its scheduler's save path; Moonrise from its holder save). Reading
 * the live chunk here is safe wherever copyOf itself is legal — the hook only ever runs
 * inside a call whose entire purpose is snapshotting that chunk's sections — and the
 * synchronization below makes the hash state safe for those off-main callers (no longer
 * just insurance). One read is wider than copyOf's own under Moonrise: its Starlight
 * mixin redirects copyOf's light reads to Starlight state, while our serializer still
 * reads {@code getDataLayerData} — safe (SWMR nibble arrays are multi-reader by design;
 * the same API feeds vanilla light packets), but "identical read set" holds only on
 * vanilla/C2ME. Known contention point, accepted for now: the whole column serialization
 * runs inside this monitor, so parallel-save systems funnel through one lock during
 * autosave storms — hoist the hash outside the lock if that ever measures hot.
 *
 * <p><b>Baselines (xaero-scatter-remediation-plan.md WI-1b, 2026-09-05).</b> An ABSENT hash
 * always reads as changed, so every chunk's first observed save per server process used
 * to mark dirty regardless of content — the first-observed-save storm: after a restart
 * every chunk any player merely LOADS (the load pyramid's light step marks it unsaved,
 * so it saves once within ~10 s) re-broadcast to every client holding it from an earlier
 * session, and the whole loaded disc re-downloaded (cold-restart-resync recorded 449
 * marked / 0 suppressed with nothing changed). Two seeders now establish the baseline
 * BEFORE the first save: the generation serve ({@link #seed}) and the chunk-LOAD seam
 * ({@link #seedLoaded} — each loader's chunk-load event, fired at FULL status when the
 * light engine already holds the chunk's data, so the hash equals the first save's
 * unless content or light genuinely changed). Eviction is LRU (coldest chunk first)
 * instead of the old wholesale clear, so table pressure forgets long-unloaded chunks,
 * never the loaded set. Entries are deliberately NOT dropped at chunk unload: the
 * loaders' unload events fire BEFORE the unload save reaches the hook, so a drop there
 * would turn every unload save into a first observation.
 */
public class DirtyContentFilter {
    /** Per-dimension cap; past it the LEAST-RECENTLY-touched entry is evicted (a chunk
     *  whose baseline was evicted re-marks dirty once at its next save — self-heals).
     *  Package-visible so the eviction test can fill exactly to the cap. */
    static final int MAX_ENTRIES_PER_DIMENSION = 512 * 1024;
    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    /** All-air columns serialize to null bytes — valid content, hashed as this sentinel
     *  so air-to-air saves stay quiet and air-to-built transitions mark dirty. */
    private static final long ALL_AIR_HASH = 0x9E3779B97F4A7C15L;

    /** Stored in place of a served-bytes hash when only the content FINGERPRINT of the
     *  baseline is known (a chunk-load seed): equal-fingerprint saves are suppressed, any
     *  other save reads as changed (fail-open). Real hashes are remapped off it. */
    static final long FINGERPRINT_ONLY = 2L;

    private final Map<String, Long2LongLinkedOpenHashMap> hashesByDimension = new HashMap<>();
    /** Pre-serialization fingerprint of the content that produced each stored hash (absent
     *  = unknown, e.g. a generation-serve seed). Always a subset of hashesByDimension's keys. */
    private final Map<String, Long2LongOpenHashMap> fingerprintsByDimension = new HashMap<>();
    private final ColumnSerializer serializer;
    private final ColumnFingerprinter fingerprinter;

    // Saves whose LOD-visible content matched the stored hash — the metadata-only re-saves this
    // filter exists to suppress (vanilla's ~10s inhabitedTime re-saves). Closes the dirty
    // conservation view: saves-observed == dirty.marked_total + dirty.suppressed_total.
    private long totalSuppressed;
    // Baselines seeded at chunk LOAD (seedLoaded) — the live instrument for whether this
    // server's chunk system fires the loaders' chunk-load event at all (Moonrise/C2ME
    // replace the vanilla status tasks); a lively server reading 0 here has no load seam.
    private long totalSeededLoads;

    /** Serializes a column to exactly the section bytes LSS serves (the hash input).
     *  Injectable for tests only — lets the exception fail-open path run without MC
     *  level/chunk objects; production always wires {@link SectionSerializer#serializeColumn}. */
    @FunctionalInterface
    interface ColumnSerializer {
        byte[] serializeSections(ServerLevel level, LevelChunk chunk, int cx, int cz);
    }

    /** A cheap fingerprint of exactly the inputs the serializer reads ({@link
     *  SectionSerializer#fingerprintColumn}): equal fingerprints guarantee equal served
     *  bytes. 0 = unavailable (the filter then serializes, as before). */
    @FunctionalInterface
    interface ColumnFingerprinter {
        long fingerprint(ServerLevel level, LevelChunk chunk, int cx, int cz);
    }

    public DirtyContentFilter() {
        this((level, chunk, cx, cz) -> SectionSerializer.serializeColumn(level, chunk, cx, cz).serializedSections(),
                SectionSerializer::fingerprintColumn);
    }

    /** Test seam (see {@link ColumnSerializer}): no fingerprints, every observation serializes. */
    DirtyContentFilter(ColumnSerializer serializer) {
        this(serializer, (level, chunk, cx, cz) -> 0L);
    }

    /** Test seam with an injected fingerprinter. */
    DirtyContentFilter(ColumnSerializer serializer, ColumnFingerprinter fingerprinter) {
        this.serializer = serializer;
        this.fingerprinter = fingerprinter;
    }

    /** Fingerprint, or 0 when unavailable or failing (the caller then serializes). */
    private long fingerprintOf(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        try {
            return this.fingerprinter.fingerprint(level, chunk, cx, cz);
        } catch (Exception e) {
            LSSLogger.debug("Dirty-content fingerprint failed for chunk " + cx + "," + cz + ": " + e);
            return 0L;
        }
    }

    /** True when the stored baseline for {@code packed} was built from content with this
     *  exact fingerprint: the served bytes are then identical, nothing to serialize. Touches
     *  the LRU entry like a stored hash would. Caller holds the monitor. */
    private boolean matchesBaseline(String dimension, long packed, long fingerprint) {
        if (fingerprint == 0L) return false;
        var fps = this.fingerprintsByDimension.get(dimension);
        if (fps == null || fps.get(packed) != fingerprint) return false;
        var hashes = this.hashesByDimension.get(dimension);
        if (hashes == null || !hashes.containsKey(packed)) return false;
        hashes.getAndMoveToLast(packed);
        return true;
    }

    /** Records (or clears, for 0) the fingerprint behind the hash just stored. */
    private void storeFingerprint(String dimension, long packed, long fingerprint) {
        if (fingerprint == 0L) {
            var fps = this.fingerprintsByDimension.get(dimension);
            if (fps != null) fps.remove(packed);
            return;
        }
        this.fingerprintsByDimension.computeIfAbsent(dimension, k -> new Long2LongOpenHashMap())
                .put(packed, fingerprint);
    }

    /**
     * Record served content as the change baseline. A serve IS a content observation:
     * seeding here means a generated chunk's first unload-save (identical bytes, since
     * serves and the save hook share the same serializer) no longer re-marks it dirty —
     * which otherwise re-sends every generated column a second time for nothing.
     */
    public synchronized void seed(String dimension, int cx, int cz, byte[] serializedSections) {
        long packed = PositionUtil.packPosition(cx, cz);
        storeHash(dimension, packed, hashContent(serializedSections));
        storeFingerprint(dimension, packed, 0L); // bytes known, content fingerprint not
    }

    /**
     * Record a just-LOADED chunk's content as its change baseline (WI-1b Option L): the
     * chunk-load seam fires at FULL status with the light engine's data already attached,
     * so the hash equals what the chunk's first save will hash unless content or light
     * genuinely changed in between — the metadata-only first save is then suppressed
     * like any later re-save. Fail-open by omission: a serialization error seeds nothing
     * (the first save then reads absent → changed, today's behavior). Runs on the
     * loaders' main thread; the serialization runs OUTSIDE the monitor. A seed racing an
     * off-main save of the same chunk is fail-open too: whichever hash lands last is a
     * real observation of the chunk, and a stale one costs one spurious mark, never a
     * missed one.
     */
    public void seedLoaded(ServerLevel level, LevelChunk chunk, String dimension) {
        seedLoaded(level, chunk, chunk.getPos().x(), chunk.getPos().z(), dimension);
    }

    /** Position-explicit body (test seam, like contentChanged's). Deliberately NOT
     *  synchronized around the serialization (review M2): this is the one path that
     *  puts a whole-column serialization on the TICK thread, and the monitor may be
     *  held by an off-main C2ME/Moonrise save worker mid-serialize — the monitor
     *  protects the hash STATE, which only {@link #storeHash} touches. */
    void seedLoaded(ServerLevel level, LevelChunk chunk, int cx, int cz, String dimension) {
        long packed = PositionUtil.packPosition(cx, cz);
        // Fast path: record only the content fingerprint (no mask rebuild, no v20
        // translation). An equal-fingerprint first save is suppressed exactly as a matching
        // hash would be; a save whose fingerprint moved serializes and reads as changed
        // against FINGERPRINT_ONLY even when its served bytes happen to match (a palette
        // re-layout with no content change within the ~10 s before the first save): fail-open.
        long fingerprint = fingerprintOf(level, chunk, cx, cz);
        if (fingerprint != 0L) {
            synchronized (this) {
                if (!matchesBaseline(dimension, packed, fingerprint)) {
                    storeHash(dimension, packed, FINGERPRINT_ONLY);
                    storeFingerprint(dimension, packed, fingerprint);
                }
                this.totalSeededLoads++;
            }
            return;
        }
        long hash;
        try {
            hash = hashContent(this.serializer.serializeSections(level, chunk, cx, cz));
        } catch (Exception e) {
            LSSLogger.debug("Dirty-content load seed failed for chunk " + cx + "," + cz + ": " + e);
            return;
        }
        synchronized (this) {
            storeHash(dimension, packed, hash);
            storeFingerprint(dimension, packed, 0L);
            this.totalSeededLoads++;
        }
    }

    /**
     * Returns true if the chunk's LOD-visible content differs from the last save we saw
     * (always true for the first observed save of a position), updating the stored hash.
     */
    public synchronized boolean contentChanged(ServerLevel level, LevelChunk chunk, String dimension) {
        return contentChanged(level, chunk, chunk.getPos().x(), chunk.getPos().z(), dimension);
    }

    /**
     * One save observation with the serialized bytes handed OUT (Phase 3 save-hook
     * deposits): {@code changed} mirrors {@link #contentChanged}; when changed AND
     * {@code depositable}, {@code sectionBytes} are the exact wire bytes just hashed
     * (null = a changed ALL-AIR column — the store's byte[0] shape). The fail-open
     * path (serialization error) is changed but NOT depositable: the caller must
     * DELETE any stored row it cannot refresh, never leave pre-edit bytes behind.
     * A record return, deliberately not a callback: the deposit runs in the CALLER,
     * structurally outside this monitor (the hook may run off-main under
     * C2ME/Moonrise).
     */
    public record SaveObservation(boolean changed, boolean depositable, byte[] sectionBytes) {
        static final SaveObservation UNCHANGED = new SaveObservation(false, false, null);
        static final SaveObservation FAIL_OPEN = new SaveObservation(true, false, null);
    }

    public synchronized SaveObservation observeSave(ServerLevel level, LevelChunk chunk,
                                                    String dimension) {
        return observeSave(level, chunk, chunk.getPos().x(), chunk.getPos().z(), dimension);
    }

    /** Position-explicit body (test seam, like contentChanged's). */
    synchronized SaveObservation observeSave(ServerLevel level, LevelChunk chunk,
                                             int cx, int cz, String dimension) {
        long packed = PositionUtil.packPosition(cx, cz);
        long fingerprint = fingerprintOf(level, chunk, cx, cz);
        if (matchesBaseline(dimension, packed, fingerprint)) {
            this.totalSuppressed++;
            return SaveObservation.UNCHANGED;
        }
        byte[] sections;
        long hash;
        try {
            sections = this.serializer.serializeSections(level, chunk, cx, cz);
            hash = hashContent(sections);
        } catch (Exception e) {
            LSSLogger.debug("Dirty-content hash failed for chunk " + cx + "," + cz + ": " + e);
            return SaveObservation.FAIL_OPEN;
        }
        boolean changed = storeHash(dimension, packed, hash);
        storeFingerprint(dimension, packed, fingerprint);
        if (!changed) {
            this.totalSuppressed++;
            return SaveObservation.UNCHANGED;
        }
        if (Boolean.getBoolean("lss.soak.dirtydebug")) {
            LSSLogger.info("[DirtyDebug] re-marked " + cx + "," + cz
                    + " hash=" + Long.toHexString(hash)
                    + " len=" + (sections == null ? 0 : sections.length));
        }
        return new SaveObservation(true, true, sections);
    }

    /** Position-explicit body; package-visible so tests can drive the injected serializer
     *  without constructing MC level/chunk objects. Synchronized (reentrant from the public
     *  overload) so the seam keeps the class's entry-points-hold-the-lock insurance. */
    synchronized boolean contentChanged(ServerLevel level, LevelChunk chunk, int cx, int cz, String dimension) {
        long packed = PositionUtil.packPosition(cx, cz);
        long fingerprint = fingerprintOf(level, chunk, cx, cz);
        if (matchesBaseline(dimension, packed, fingerprint)) {
            this.totalSuppressed++;
            return false;
        }
        long hash;
        int lastLen;
        try {
            byte[] sections = this.serializer.serializeSections(level, chunk, cx, cz);
            hash = hashContent(sections);
            lastLen = sections == null ? 0 : sections.length;
        } catch (Exception e) {
            LSSLogger.debug("Dirty-content hash failed for chunk " + cx + "," + cz + ": " + e);
            return true;
        }

        boolean changed = storeHash(dimension, packed, hash);
        storeFingerprint(dimension, packed, fingerprint);
        if (!changed) this.totalSuppressed++; // metadata-only re-save the filter suppressed
        if (changed && Boolean.getBoolean("lss.soak.dirtydebug")) {
            LSSLogger.info("[DirtyDebug] re-marked " + cx + "," + cz
                    + " hash=" + Long.toHexString(hash) + " len=" + lastLen);
        }
        return changed;
    }

    /** Stores the hash; returns true if it differs from the previous value (or none existed).
     *  Package-visible for testing the overflow-eviction path; synchronized (reentrant from the
     *  public methods) so the seam keeps the class's entry-points-hold-the-lock insurance. */
    synchronized boolean storeHash(String dimension, long packed, long hash) {
        var hashes = this.hashesByDimension.computeIfAbsent(dimension, k -> {
            var map = new Long2LongLinkedOpenHashMap();
            map.defaultReturnValue(0L);
            return map;
        });
        // LRU: a touched entry moves to the tail; past the cap the HEAD (least recently
        // touched) goes. A plain put would keep INSERTION order and evict the hottest
        // (oldest-inserted, most re-saved) chunks first (panel fold, Opus A M4).
        if (hashes.size() >= MAX_ENTRIES_PER_DIMENSION && !hashes.containsKey(packed)) {
            long evicted = hashes.firstLongKey();
            hashes.removeFirstLong();
            var fps = this.fingerprintsByDimension.get(dimension);
            if (fps != null) fps.remove(evicted);
        }
        long previous = hashes.putAndMoveToLast(packed, hash);
        return previous != hash;
    }

    /** Cumulative count of suppressed metadata-only re-saves (see {@link #totalSuppressed}). */
    public synchronized long getTotalSuppressed() {
        return this.totalSuppressed;
    }

    /** Forget a baseline (the 2.x lifecycle module's {@code CHUNK_GENERATE} twin of the
     *  newly-generated skip): the chunk's next save reads absent → changed, as before. */
    public synchronized void forget(String dimension, int cx, int cz) {
        long packed = PositionUtil.packPosition(cx, cz);
        var hashes = this.hashesByDimension.get(dimension);
        if (hashes != null) hashes.remove(packed);
        var fps = this.fingerprintsByDimension.get(dimension);
        if (fps != null) fps.remove(packed);
    }

    /** Cumulative count of chunk-load baselines (see {@link #totalSeededLoads}). */
    public synchronized long getTotalSeededLoads() {
        return this.totalSeededLoads;
    }

    /** Live baseline count across dimensions — a GAUGE (falls on eviction, zero after
     *  a restart): never a monotonic exporter field. */
    public synchronized int entryCount() {
        int n = 0;
        for (var hashes : this.hashesByDimension.values()) n += hashes.size();
        return n;
    }

    private static long hashContent(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? ALL_AIR_HASH : fnv1a64(bytes);
    }

    private static long fnv1a64(byte[] bytes) {
        long h = FNV_OFFSET;
        for (byte b : bytes) {
            h ^= (b & 0xFF);
            h *= FNV_PRIME;
        }
        return remapAbsentSentinel(h);
    }

    /** 0 is the map's absent sentinel — remap so a real hash never collides with "absent"
     *  (a stored 0 would make the first observation read as unchanged). Package-visible:
     *  no constructible input has a raw FNV-1a of 0, so the remap is only pinnable in isolation. */
    static long remapAbsentSentinel(long h) {
        return h == 0L ? 1L : h == FINGERPRINT_ONLY ? 3L : h;
    }
}
