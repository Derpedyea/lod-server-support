package dev.vox.lss.networking.server;

import dev.vox.lss.common.processing.LoadedColumnData;
import dev.vox.lss.compat.AntiXrayCompat;
import io.netty.buffer.Unpooled;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

/**
 * Serializes loaded chunk columns into MC-native wire format.
 * Uses {@link LevelChunkSection#write(FriendlyByteBuf)} for block states + biomes,
 * plus raw DataLayer nibble bytes for light data.
 *
 * <p>Thread contract: the serve paths call this on the server thread; the dirty-detection
 * hook (issue #69 retarget) may additionally call it from whatever thread legally runs
 * {@code SerializableChunkData.copyOf} — C2ME/Moonrise save workers — where reading the
 * section and light data is safe because copyOf itself performs the same class of reads
 * there (under Moonrise the light layers come from Starlight's SWMR nibble arrays, which
 * are multi-reader by design).
 */
public final class SectionSerializer {
    private SectionSerializer() {}

    /**
     * Serialize all non-air sections of a loaded chunk column into MC-native wire format.
     * Returns a {@link LoadedColumnData} with pre-serialized bytes.
     */
    private record SectionInfo(int index, int sectionY, SectionPos sectionPos,
                               DataLayer blLayer, boolean hasBlockLight,
                               DataLayer slLayer, boolean hasSkyLight) {}

    public static LoadedColumnData serializeColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        // One AntiXray-shim scope per COLUMN (not per section): section.write crashes under
        // the AntiXray mod's mixins outside this binding — see AntiXrayCompat.
        return AntiXrayCompat.callSerializing(() -> serializeColumnInner(level, chunk, cx, cz));
    }

    /** Pass 1 + the band rule: the sections a column serves, with their light layers
     *  cached. Shared by the serializer and {@link #fingerprintColumn}, so the two can
     *  never disagree about what is served. */
    private static java.util.List<SectionInfo> includedSections(ServerLevel level, LevelChunkSection[] sections,
                                                                int cx, int cz) {
        int minSectionY = level.getMinSectionY();
        var lightEngine = level.getLightEngine();
        var blockLightListener = lightEngine.getLayerListener(LightLayer.BLOCK);
        var skyLightListener = lightEngine.getLayerListener(LightLayer.SKY);

        // First pass: collect non-air sections and cache light results. Air-only sections
        // WITH stored non-zero sky light are served too (2026-07-27, black-boundary-faces
        // fix): vanilla's light grid extends one section past terrain, and those boundary
        // air layers are exactly what lights the top/side faces of the terrain below/beside
        // them at a chunk border. A consumer sampling a never-served neighbor section gets
        // light 0 — leaves rendered black from one side. Fully-dark air (caves, deep
        // enclosures) still skips: its layers are null/zero, so the serve set stays
        // bounded to vanilla's own stored-light coverage (~1-2 extra sections per column).
        var includedSections = new java.util.ArrayList<SectionInfo>(sections.length);
        for (int i = 0; i < sections.length; i++) {
            var section = sections[i];
            if (section == null) continue;
            int sectionY = minSectionY + i;
            var sectionPos = SectionPos.of(cx, sectionY, cz);
            var blLayer = blockLightListener.getDataLayerData(sectionPos);
            boolean hasBlockLight = blLayer != null && hasNonZeroData(blLayer);
            var slLayer = skyLightListener.getDataLayerData(sectionPos);
            boolean hasSkyLight = slLayer != null && hasNonZeroData(slLayer);

            if (section.hasOnlyAir() && !hasBlockLight && !hasSkyLight) continue;

            includedSections.add(new SectionInfo(i, sectionY, sectionPos,
                    blLayer, hasBlockLight, slLayer, hasSkyLight));
        }

        // Band rule, same as the NBT path (disk/live byte parity): SKY-lit air serves only
        // within one section of the content band — vanilla's own stored-light coverage —
        // so a void/cleared column's ambient sky can never turn a zero-section CLEAR into
        // a data column. BLOCK-lit air keeps its long-standing unconditional serve.
        int minContent = Integer.MAX_VALUE, maxContent = Integer.MIN_VALUE;
        for (var info : includedSections) {
            if (!sections[info.index()].hasOnlyAir()) {
                minContent = Math.min(minContent, info.sectionY());
                maxContent = Math.max(maxContent, info.sectionY());
            }
        }
        final boolean noContent = minContent == Integer.MAX_VALUE;
        final int lo = minContent - 1, hi = maxContent + 1;
        includedSections.removeIf(info -> sections[info.index()].hasOnlyAir()
                && !info.hasBlockLight()
                && (noContent || info.sectionY() < lo || info.sectionY() > hi));
        return includedSections;
    }

    private static LoadedColumnData serializeColumnInner(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        var sections = chunk.getSections();
        var includedSections = includedSections(level, sections, cx, cz);

        if (includedSections.isEmpty()) {
            return new LoadedColumnData(cx, cz, null, 0);
        }

        // Second pass: serialize using cached results
        var maskEntry = XrayMaskManager.entryForActive(level);
        var maskFactory = maskEntry != null ? NbtSectionSerializer.factoryFor(level.registryAccess()) : null;
        var buf = new FriendlyByteBuf(Unpooled.buffer(sections.length * 1024));
        try {
            buf.writeVarInt(includedSections.size());

            for (var info : includedSections) {
                var section = sections[info.index];
                buf.writeByte(info.sectionY);
                // Masking INSIDE the choke point: probe, generation, and the
                // DirtyContentFilter hash all see identical masked bytes by construction.
                // maskAndWrite emits the masked section's exact wire bytes without building
                // the section object (no per-cell recalcBlockCounts); -1 = nothing to mask.
                int replacedCells = maskEntry == null ? -1 : XrayMaskFilter.maskAndWrite(section, info.sectionY,
                        maskEntry.mask(), maskEntry.kind(), maskFactory, buf);
                if (replacedCells < 0) {
                    section.write(buf);
                } else if (replacedCells > 0) {
                    // Count only when cells were actually hidden — a stale-palette
                    // rebuild (mined-out ore still listed) swaps for the prune only.
                    var manager = XrayMaskManager.current();
                    if (manager != null) manager.countMaskedSection();
                }

                // Block light (cached from pass 1)
                buf.writeBoolean(info.hasBlockLight);
                if (info.hasBlockLight) {
                    buf.writeBytes(info.blLayer.getData());
                }

                // Sky light (cached from pass 1)
                buf.writeBoolean(info.hasSkyLight);
                if (info.hasSkyLight) {
                    buf.writeBytes(info.slLayer.getData());
                }
            }

            byte[] nativeBody = new byte[buf.readableBytes()];
            buf.readBytes(nativeBody);
            // C1 produce-path v20 hook (progress-doc decision 2026-08-07): the native
            // emit above is unchanged; the v20 body is its canonical translation. Live,
            // generation (which reuses this method), and the DirtyContentFilter hash all
            // see the same translated bytes.
            byte[] serialized = NbtSectionSerializer.toV20(nativeBody, level.registryAccess());
            return new LoadedColumnData(cx, cz, serialized, serialized.length);
        } finally {
            buf.release();
        }
    }

    /** Reusable per-thread scratch for {@link #fingerprintColumn}: a heap buffer, so its
     *  backing array can be hashed directly. */
    private static final ThreadLocal<FriendlyByteBuf> FINGERPRINT_BUF =
            ThreadLocal.withInitial(() -> new FriendlyByteBuf(Unpooled.buffer(16 * 1024)));

    /**
     * A cheap fingerprint of every input {@link #serializeColumn} reads: the served section
     * set, each section's UNMASKED native bytes ({@code section.write}: counts, palettes,
     * bit storage, biomes), its light flags and bytes, and the active x-ray mask's
     * semantics. Masking, the v20 translation and the registry are deterministic given
     * those, so equal fingerprints mean equal served bytes; the converse does not hold
     * (a palette re-layout without a content change gives a new fingerprint), which only
     * costs a real serialization. Skips the mask rebuild and the v20 translation, the two
     * costly steps. Never returns 0 (0 = "no fingerprint" to callers).
     */
    public static long fingerprintColumn(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        return AntiXrayCompat.callSerializing(() -> fingerprintColumnInner(level, chunk, cx, cz));
    }

    private static long fingerprintColumnInner(ServerLevel level, LevelChunk chunk, int cx, int cz) {
        var sections = chunk.getSections();
        var included = includedSections(level, sections, cx, cz);
        var h = new ContentHasher();
        h.add(included.size());
        var maskEntry = XrayMaskManager.entryForActive(level);
        if (maskEntry == null) {
            h.add(0L);
        } else {
            h.add(maskEntry.mask().isEmpty() ? 1L : maskEntry.mask().fingerprint());
            h.add(maskEntry.mask().maxBlockHeight());
            h.add(maskEntry.kind().ordinal());
        }
        var buf = FINGERPRINT_BUF.get();
        for (var info : included) {
            h.add(info.sectionY);
            buf.clear();
            sections[info.index].write(buf);
            h.add(buf.array(), buf.arrayOffset(), buf.writerIndex());
            h.add(info.hasBlockLight ? 1L : 0L);
            if (info.hasBlockLight) h.add(info.blLayer.getData());
            h.add(info.hasSkyLight ? 1L : 0L);
            if (info.hasSkyLight) h.add(info.slLayer.getData());
        }
        if (buf.capacity() > 256 * 1024) FINGERPRINT_BUF.remove(); // don't pin an outlier
        return h.finish();
    }

    private static boolean hasNonZeroData(DataLayer layer) {
        for (byte b : layer.getData()) {
            if (b != 0) return true;
        }
        return false;
    }
}
