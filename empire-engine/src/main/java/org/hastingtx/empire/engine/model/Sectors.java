package org.hastingtx.empire.engine.model;

import java.util.AbstractList;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Consumer;

/**
 * The world's sectors, immutable, with copy-on-write by the chunk rather than by the world
 * (issue #89).
 *
 * <p>{@code World.withSector} used to copy the whole list — twice, once into an {@code ArrayList} and
 * again in the record's compact constructor — to change one hex. At 512x1024 that is 524,288 references
 * copied per call, measured at 10.1 ms, and a country's agents issue hundreds of commands an update.
 *
 * <p>Here the sectors live in fixed chunks of {@value #CHUNK} behind an array of chunks. Changing one
 * sector copies the chunk array and the one chunk it falls in, and shares every other chunk with the
 * world it came from: 1,536 references instead of 524,288 at that size, 3,072 instead of 2,097,152 at
 * the largest map we generate. The chunk size is near the optimum — the cost is
 * {@code n/CHUNK + CHUNK}, minimised around the square root of the sector count — across the whole
 * range of map sizes, and being a power of two keeps the arithmetic to a shift and a mask.
 *
 * <p>It is a {@link List} so nothing else had to move. The default {@link AbstractList} iterator goes
 * through {@link #get} and a bounds check per element, which the update's scans would feel, so
 * iteration and {@link #forEach} walk the chunks directly.
 *
 * <p>Nothing here mutates: {@code with} returns a new instance and the shared chunks are never written
 * to. That is what makes the update's frozen snapshot free.
 */
public final class Sectors extends AbstractList<Sector> {

    static final int CHUNK_BITS = 10;
    static final int CHUNK = 1 << CHUNK_BITS;
    private static final int MASK = CHUNK - 1;

    private final Sector[][] chunks;
    private final int size;

    private Sectors(Sector[][] chunks, int size) { this.chunks = chunks; this.size = size; }

    /** The same instance if it is already one of these, otherwise a chunked copy. */
    public static Sectors of(Collection<? extends Sector> src) {
        if (src instanceof Sectors s) return s;
        int size = src.size();
        int n = (size + CHUNK - 1) / CHUNK;
        Sector[][] chunks = new Sector[n][];
        for (int c = 0; c < n; c++) chunks[c] = new Sector[Math.min(CHUNK, size - c * CHUNK)];
        int i = 0;
        for (Sector s : src) { chunks[i >> CHUNK_BITS][i & MASK] = s; i++; }
        return new Sectors(chunks, size);
    }

    @Override public Sector get(int i) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException("sector " + i + " of " + size);
        return chunks[i >> CHUNK_BITS][i & MASK];
    }

    @Override public int size() { return size; }

    /** This list with sector {@code i} replaced. Copies one chunk and the chunk array; shares the rest. */
    public Sectors with(int i, Sector s) {
        if (i < 0 || i >= size) throw new IndexOutOfBoundsException("sector " + i + " of " + size);
        int c = i >> CHUNK_BITS;
        if (chunks[c][i & MASK] == s) return this;
        Sector[][] next = chunks.clone();
        next[c] = chunks[c].clone();
        next[c][i & MASK] = s;
        return new Sectors(next, size);
    }

    @Override public void forEach(Consumer<? super Sector> action) {
        for (Sector[] chunk : chunks) for (Sector s : chunk) action.accept(s);
    }

    @Override public Iterator<Sector> iterator() {
        return new Iterator<>() {
            private int i;
            @Override public boolean hasNext() { return i < size; }
            @Override public Sector next() {
                if (i >= size) throw new NoSuchElementException();
                Sector s = chunks[i >> CHUNK_BITS][i & MASK];
                i++;
                return s;
            }
        };
    }

    /** A plain mutable copy, for callers that want to build a new world by hand. */
    public List<Sector> toList() {
        List<Sector> out = new java.util.ArrayList<>(size);
        forEach(out::add);
        return out;
    }
}
