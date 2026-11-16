package dev.vox.lss.networking.server;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Streaming 64-bit content hash (xxHash64's per-lane round + avalanche, one lane) for the
 * dirty filter's pre-serialization fingerprint. Reads bytes eight at a time, so hashing a
 * section's raw wire bytes + light costs a few microseconds. Not cryptographic: it only has
 * to tell real content apart, like the FNV hash it sits in front of.
 */
final class ContentHasher {
    private static final VarHandle LONGS =
            MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final long P1 = 0x9E3779B185EBCA87L;
    private static final long P2 = 0xC2B2AE3D27D4EB4FL;
    private static final long P3 = 0x165667B19E3779F9L;
    private static final long P4 = 0x85EBCA77C2B2AE63L;

    private long h = P3;
    private long words;

    void add(long v) {
        v *= P2;
        v = Long.rotateLeft(v, 31);
        v *= P1;
        this.h ^= v;
        this.h = Long.rotateLeft(this.h, 27) * P1 + P4;
        this.words++;
    }

    /** Length-prefixed, so adjacent arrays never alias each other's boundaries. */
    void add(byte[] a, int off, int len) {
        add(len);
        int i = 0;
        for (; i + 8 <= len; i += 8) add((long) LONGS.get(a, off + i));
        if (i < len) {
            long tail = 0;
            for (int s = 0; i < len; i++, s += 8) tail |= (a[off + i] & 0xFFL) << s;
            add(tail);
        }
    }

    void add(byte[] a) {
        add(a, 0, a.length);
    }

    /** Never 0: 0 means "no fingerprint" to the dirty filter. */
    long finish() {
        long x = this.h ^ this.words;
        x ^= x >>> 33;
        x *= P2;
        x ^= x >>> 29;
        x *= P3;
        x ^= x >>> 32;
        return x == 0L ? 1L : x;
    }
}
