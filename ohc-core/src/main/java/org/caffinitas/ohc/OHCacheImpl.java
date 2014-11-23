/*
 *      Copyright (C) 2014 Robert Stupp, Koeln, Germany, robert-stupp.de
 *
 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */
package org.caffinitas.ohc;

import java.io.IOError;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.cache.CacheStats;
import com.google.common.collect.AbstractIterator;
import com.google.common.collect.ImmutableMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class OHCacheImpl<K, V> implements OHCache<K, V>
{

    private static final Logger LOGGER = LoggerFactory.getLogger(OHCacheImpl.class);

    static
    {
        try
        {
            Field f = AtomicLong.class.getDeclaredField("VM_SUPPORTS_LONG_CAS");
            f.setAccessible(true);
            if (!(Boolean) f.get(null))
                throw new IllegalStateException("Off Heap Cache implementation requires a JVM that supports CAS on long fields");
        }
        catch (Exception e)
        {
            throw new RuntimeException();
        }
    }

    public static final int MIN_HASH_TABLE_SIZE = 32;
    public static final int MAX_BLOCK_SIZE = 262144;
    public static final int MIN_BLOCK_SIZE = 128;
    private static final int MAXIMUM_INT = 1 << 30;

    private final int blockSize;
    private final long capacity;
    private final double cleanUpTrigger;
    private final CacheSerializer<K> keySerializer;
    private final CacheSerializer<V> valueSerializer;

    private final Uns uns;
    private final FreeBlocks freeBlocks;
    private final HashEntryAccess hashEntryAccess;
    private final HashPartitionAccess hashPartitionAccess;
    private final int lruListLenTrigger;

    private volatile boolean closed;

    private final ScheduledExecutorService executorService;

    private volatile boolean statisticsEnabled;

    private final AtomicLong hitCount = new AtomicLong();
    private final AtomicLong missCount = new AtomicLong();
    private final AtomicLong loadSuccessCount = new AtomicLong();
    private final AtomicLong loadExceptionCount = new AtomicLong();
    private final AtomicLong totalLoadTime = new AtomicLong();
    private final AtomicLong evictionCount = new AtomicLong();
    private final AtomicInteger rehashCount = new AtomicInteger();

    private final AtomicBoolean globalLock = new AtomicBoolean();

    OHCacheImpl(OHCacheBuilder<K, V> builder)
    {
        if (builder.getBlockSize() < MIN_BLOCK_SIZE)
            throw new IllegalArgumentException("Block size must not be less than " + MIN_BLOCK_SIZE + " is (" + builder.getBlockSize() + ')');
        if (builder.getBlockSize() > MAX_BLOCK_SIZE)
            throw new IllegalArgumentException("Block size must not be greater than " + MAX_BLOCK_SIZE + " is (" + builder.getBlockSize() + ')');
        int bs = roundUpToPowerOf2(builder.getBlockSize());
        if (bs != builder.getBlockSize())
            LOGGER.warn("Using block size {} instead of configured block size {} - adjust your configuration to be precise", bs, builder.getBlockSize());
        blockSize = bs;

        long minSize = 8 * 1024 * 1024; // very small

        long cap = builder.getCapacity();
        cap /= bs;
        cap *= bs;
        if (cap < minSize)
            throw new IllegalArgumentException("Total size must not be less than " + minSize + " is (" + builder.getCapacity() + ')');
        if (cap != builder.getCapacity())
            LOGGER.warn("Using capacity {} instead of configured capacity {} - adjust your configuration to be precise", cap, builder.getCapacity());
        capacity = cap;

        int hts = builder.getHashTableSize();
        if (hts > 0)
        {
            if (hts < MIN_HASH_TABLE_SIZE)
                throw new IllegalArgumentException("Block size must not be less than " + MIN_HASH_TABLE_SIZE + " is (" + hts + ')');
            hts = roundUpToPowerOf2(hts);
            if (hts != builder.getHashTableSize())
                LOGGER.warn("Using hash table size {} instead of configured hash table size {} - adjust your configuration to be precise", hts, builder.getHashTableSize());
        }
        else
        {
            // auto-size hash table
            int blockCount = (int) (cap / bs);
            hts = blockCount / 16;
        }

        int lruListLenTrigger = builder.getLruListLenTrigger();
        if (lruListLenTrigger < 1)
            lruListLenTrigger = 1;
        this.lruListLenTrigger = lruListLenTrigger;

        this.uns = new Uns(capacity, blockSize);
        try
        {
            this.hashPartitionAccess = new HashPartitionAccess(hts);
            this.freeBlocks = new FreeBlocks(uns, capacity, blockSize);
            this.hashEntryAccess = new HashEntryAccess(uns, blockSize, freeBlocks, hashPartitionAccess, lruListLenTrigger);

            this.keySerializer = builder.getKeySerializer();
            this.valueSerializer = builder.getValueSerializer();

            double cut = builder.getCleanUpTrigger();
            if (cut < 0d || cut > 1d)
                throw new IllegalArgumentException("Invalid clean-up percentage trigger value " + String.format("%.2f", cut));
            this.cleanUpTrigger = cut;

            long cleanupCheckInterval = builder.getCleanupCheckInterval();

            if (cut <= 0d && cleanupCheckInterval > 0L)
                throw new IllegalArgumentException("Incompatible settings - cleanup-check-interval " +
                                                   cleanupCheckInterval + " vs cleanup-trigger " + String.format("%.2f", cut));


            this.statisticsEnabled = builder.isStatisticsEnabled();

            ScheduledExecutorService es = null;
            if (cleanupCheckInterval > 0)
            {
                es = Executors.newScheduledThreadPool(1);
                es.scheduleWithFixedDelay(new Runnable()
                {
                    public void run()
                    {
                        cleanUp();
                    }
                }, cleanupCheckInterval, cleanupCheckInterval, TimeUnit.MILLISECONDS);
            }
            executorService = es;

            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Initialized OHC with capacity={}, hash-table-size={}, block-size={}", cap, hts, bs);
        }
        catch (Throwable t)
        {
            uns.free();
            if (t instanceof RuntimeException)
                throw (RuntimeException) t;
            if (t instanceof Error)
                throw (Error) t;
            throw new RuntimeException(t);
        }
    }

    private static int roundUpToPowerOf2(int number)
    {
        return number >= MAXIMUM_INT
               ? MAXIMUM_INT
               : (number > 1) ? Integer.highestOneBit((number - 1) << 1) : 1;
    }

    public void close()
    {
        if (closed)
            return;

        closed = true;

        if (LOGGER.isDebugEnabled())
            LOGGER.debug("Closing OHC instance");

        if (executorService != null)
        {
            executorService.shutdown();
            try
            {
                executorService.awaitTermination(60, TimeUnit.SECONDS);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        }

        // releasing memory immediately is dangerous since other threads may still access the data
        // Need to orderly clear the cache before releasing (this involves hash-partition and hash-entry locks,
        // which ensure that no other thread is accessing OHC)
        removeAllInt();

        uns.free();
    }

    private void removeAllInt()
    {
        for (int h = 0; h < getHashTableSize(); h++)
        {
            long lruHead;
            long partitionAdr = hashPartitionAccess.lockPartitionForHash(h);
            try
            {
                lruHead = hashPartitionAccess.getLRUHead(partitionAdr);
                hashPartitionAccess.setLRUHead(partitionAdr, 0L);
            }
            finally
            {
                hashPartitionAccess.unlockPartition(partitionAdr);
            }

            for (long hashEntryAdr = lruHead; hashEntryAdr != 0L; hashEntryAdr = hashEntryAccess.getLRUNext(hashEntryAdr))
            {
                hashEntryAccess.lockEntry(hashEntryAdr);
                freeBlocks.freeChain(hashEntryAdr);
                // do NOT unlock - data block might be used elsewhere
            }
        }
    }

    int[] calcLruListLengths()
    {
        int[] ll = new int[getHashTableSize()];
        for (int h = 0; h < ll.length; h++)
        {
            long partitionAdr = hashPartitionAccess.lockPartitionForHash(h);
            try
            {
                int l = 0;
                for (long hashEntryAdr = hashPartitionAccess.getLRUHead(partitionAdr); hashEntryAdr != 0L; hashEntryAdr = hashEntryAccess.getLRUNext(hashEntryAdr))
                    l++;
                ll[h] = l;
            }
            finally
            {
                hashPartitionAccess.unlockPartition(partitionAdr);
            }
        }
        return ll;
    }

    public boolean isStatisticsEnabled()
    {
        return statisticsEnabled;
    }

    public void setStatisticsEnabled(boolean statisticsEnabled)
    {
        this.statisticsEnabled = statisticsEnabled;
    }

    private void assertNotClosed()
    {
        if (closed)
            throw new IllegalStateException("OHCache instance already closed");
    }

    public int getBlockSize()
    {
        return blockSize;
    }

    public long getCapacity()
    {
        return capacity;
    }

    public int getHashTableSize()
    {
        return hashPartitionAccess.getHashTableSize();
    }

    public int calcFreeBlockCount()
    {
        assertNotClosed();

        return freeBlocks.calcFreeBlockCount();
    }

    public long getMemUsed()
    {
        return capacity - calcFreeBlockCount() * blockSize;
    }

    public PutResult put(int hash, BytesSource keySource, BytesSource valueSource)
    {
        return put(hash, keySource, valueSource, null);
    }

    public PutResult put(int hash, BytesSource keySource, BytesSource valueSource, BytesSink oldValueSink)
    {
        assertNotClosed();

        if (keySource == null)
            throw new NullPointerException();
        if (valueSource == null)
            throw new NullPointerException();
        if (keySource.size() <= 0)
            throw new ArrayIndexOutOfBoundsException();
        if (valueSource.size() < 0)
            throw new ArrayIndexOutOfBoundsException();

        // Allocate and fill new hash entry.
        // Do this outside of the hash-partition lock to hold that lock no longer than necessary.
        long newHashEntryAdr = hashEntryAccess.createNewEntryChain(hash, keySource, valueSource, -1L);
        if (newHashEntryAdr == 0L)
        {
            remove(hash, keySource);
            return PutResult.NO_MORE_SPACE;
        }

        return putInternal(hash, keySource, oldValueSink, newHashEntryAdr);
    }

    private PutResult putInternal(int hash, BytesSource keySource, BytesSink oldValueSink, long newHashEntryAdr)
    {
        long oldHashEntryAdr;

        // find + lock hash partition
        long partitionAdr = hashPartitionAccess.lockPartitionForHash(hash);
        try
        {
            // find existing entry
            oldHashEntryAdr = hashEntryAccess.findHashEntry(partitionAdr, hash, keySource);

            // remove existing entry
            if (oldHashEntryAdr != 0L)
                hashEntryAccess.removeFromPartitionLRU(partitionAdr, oldHashEntryAdr);

            // add new entry
            hashEntryAccess.addAsPartitionLRUHead(partitionAdr, newHashEntryAdr);

            // We have to lock the old entry before we can actually free the allocated blocks.
            // There's no need for a corresponding unlock because we use CAS on a field for locking.
            hashEntryAccess.lockEntry(oldHashEntryAdr);
        }
        finally
        {
            // release hash partition
            hashPartitionAccess.unlockPartition(partitionAdr);
        }

        // No old entry - just return.
        if (oldHashEntryAdr == 0L)
            return PutResult.ADD;

        try
        {
            // Write old value (if wanted).
            if (oldValueSink != null)
                hashEntryAccess.writeValueToSink(oldHashEntryAdr, oldValueSink);
        }
        finally
        {
            // release old value
            freeBlocks.freeChain(oldHashEntryAdr);
        }

        return PutResult.REPLACE;
    }

    public boolean get(int hash, BytesSource keySource, BytesSink valueSink)
    {
        assertNotClosed();

        if (keySource == null)
            throw new NullPointerException();
        if (valueSink == null)
            throw new NullPointerException();
        if (keySource.size() <= 0)
            throw new ArrayIndexOutOfBoundsException();

        long hashEntryAdr = getInternal(hash, keySource);

        if (hashEntryAdr == 0L)
            return false;

        // Write the value to the caller and unlock the entry.
        try
        {
            hashEntryAccess.writeValueToSink(hashEntryAdr, valueSink);
        }
        finally
        {
            hashEntryAccess.unlockEntry(hashEntryAdr);
        }

        return true;
    }

    private long getInternal(int hash, BytesSource keySource)
    {
        // find + lock hash partition
        long partitionAdr = hashPartitionAccess.lockPartitionForHash(hash);
        long hashEntryAdr;
        try
        {
            // find hash entry
            hashEntryAdr = hashEntryAccess.findHashEntry(partitionAdr, hash, keySource);
            if (hashEntryAdr != 0L)
            {
                hashEntryAccess.updatePartitionLRU(partitionAdr, hashEntryAdr);

                // to keep the hash-partition lock short, lock the entry here
                hashEntryAccess.lockEntry(hashEntryAdr);
            }
        }
        finally
        {
            // release hash partition
            hashPartitionAccess.unlockPartition(partitionAdr);
        }

        if (statisticsEnabled)
            (hashEntryAdr == 0L ? missCount : hitCount).incrementAndGet();

        return hashEntryAdr;
    }

    public boolean remove(int hash, BytesSource keySource)
    {
        assertNotClosed();

        if (keySource == null)
            throw new NullPointerException();
        if (keySource.size() <= 0)
            throw new ArrayIndexOutOfBoundsException();

        // find + lock hash partition
        long hashEntryAdr;
        long partitionAdr = hashPartitionAccess.lockPartitionForHash(hash);
        try
        {
            // find hash entry
            hashEntryAdr = hashEntryAccess.findHashEntry(partitionAdr, hash, keySource);
            if (hashEntryAdr == 0L)
                return false;

            hashEntryAccess.removeFromPartitionLRU(partitionAdr, hashEntryAdr);

            // We have to lock the old entry before we can actually free the allocated blocks.
            // There's no need for a corresponding unlock because we use CAS on a field for locking.
            hashEntryAccess.lockEntry(hashEntryAdr);
        }
        finally
        {
            // release hash partition
            hashPartitionAccess.unlockPartition(partitionAdr);
        }

        // free chain
        freeBlocks.freeChain(hashEntryAdr);
        // do NOT unlock - data block might be used elsewhere

        return true;
    }

    public long getFreeBlockSpins()
    {
        return freeBlocks.getFreeBlockSpins();
    }

    public long getLockPartitionSpins()
    {
        return hashPartitionAccess.getLockPartitionSpins();
    }

    public void invalidate(Object o)
    {
        BytesSource.ByteArraySource ks = keySource((K) o);

        remove(ks.hashCode(), ks);
    }

    public V getIfPresent(Object o)
    {
        assertNotClosed();

        if (valueSerializer == null)
            throw new NullPointerException("no valueSerializer configured");

        BytesSource.ByteArraySource ks = keySource((K) o);

        long hashEntryAdr = getInternal(ks.hashCode(), ks);
        if (hashEntryAdr == 0L)
            return null;

        try
        {
            return valueSerializer.deserialize(hashEntryAccess.readValueFrom(hashEntryAdr));
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
        finally
        {
            hashEntryAccess.unlockEntry(hashEntryAdr);
        }
    }

    private BytesSource.ByteArraySource keySource(K o)
    {
        if (keySerializer == null)
            throw new NullPointerException("no keySerializer configured");
        long size = keySerializer.serializedSize(o);
        if (size < 0)
            throw new IllegalArgumentException();
        if (size >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("serialized size of key too large (>2GB)");

        byte[] tmp = new byte[(int) size];
        try
        {
            keySerializer.serialize(o, new ByteArrayOut(tmp));
        }
        catch (IOException e)
        {
            throw new IOError(e);
        }
        return new BytesSource.ByteArraySource(tmp);
    }

    public void put(K k, V v)
    {
        assertNotClosed();

        if (valueSerializer == null)
            throw new NullPointerException("no valueSerializer configured");

        BytesSource.ByteArraySource ks = keySource(k);
        int hash = ks.hashCode();
        long valueLen = valueSerializer.serializedSize(v);

        // Allocate and fill new hash entry.
        // Do this outside of the hash-partition lock to hold that lock no longer than necessary.
        long newHashEntryAdr = hashEntryAccess.createNewEntryChain(hash, ks, null, valueLen);
        if (newHashEntryAdr == 0L)
        {
            remove(ks.hashCode(), ks);
            return;
        }

        try
        {
            hashEntryAccess.valueToHashEntry(newHashEntryAdr, valueSerializer, v, ks.size(), valueLen);
        }
        catch (IOException e)
        {
            freeBlocks.freeChain(newHashEntryAdr);
            throw new IOError(e);
        }

        putInternal(hash, ks, null, newHashEntryAdr);
    }

    public boolean rehash()
    {
        assertNotClosed();

        if (!globalLock.compareAndSet(false, true))
            return false;
        try
        {

            boolean rehashRequired = false;

            for (int h = 0; h < getHashTableSize() && !rehashRequired; h++)
            {
                long partAdr = hashPartitionAccess.lockPartitionForHash(h);
                try
                {
                    long lruHead = hashPartitionAccess.getLRUHead(partAdr);
                    int listLen = 0;
                    for (long hashEntryAdr = lruHead; hashEntryAdr != 0L; hashEntryAdr = hashEntryAccess.getLRUNext(hashEntryAdr))
                        listLen++;

                    if (listLen >= lruListLenTrigger)
                        rehashRequired = true;
                }
                finally
                {
                    hashPartitionAccess.unlockPartition(partAdr);
                }
            }

            if (rehashRequired)
                rehashInt();

            return rehashRequired;
        }
        finally
        {
            globalLock.set(false);
        }
    }

    public void cleanUp()
    {
        assertNotClosed();

        if (!globalLock.compareAndSet(false, true))
            return;
        try
        {
            int totalBlockCount = (int) (capacity / blockSize);
            int freeBlockCount = calcFreeBlockCount();
            double fsPerc = ((double) freeBlockCount) / totalBlockCount;

            if (fsPerc > cleanUpTrigger)
                return;

            long entries = size();

            double blocksPerEntry = totalBlockCount - freeBlockCount;
            blocksPerEntry /= entries;

            int expectedFreeBlockCount = (int) (cleanUpTrigger * totalBlockCount);
            int entriesToRemove = (int) ((expectedFreeBlockCount - freeBlockCount) * blocksPerEntry);
            int entriesToRemovePerPartition = (int) ((expectedFreeBlockCount - freeBlockCount) * blocksPerEntry / getHashTableSize());
            if (entriesToRemovePerPartition < 1)
                entriesToRemovePerPartition = 1;
            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Cleanup starts with free-space percentage {}, entries={}, blocks-per-entry={}, entries-to-remove={}",
                             String.format("%.2f", fsPerc),
                             entries,
                             String.format("%.2f", blocksPerEntry),
                             entriesToRemove
                );

            int blocksFreed = 0;
            int entriesRemoved = 0;

            boolean rehashRequired = false;

            for (int h = 0; h < getHashTableSize(); h++)
            {
                long startAt = 0L;

                long partAdr = hashPartitionAccess.lockPartitionForHash(h);
                try
                {
                    long lastHashEntryAdr = 0L;
                    long lruHead = hashPartitionAccess.getLRUHead(partAdr);
                    int listLen = 0;
                    for (long hashEntryAdr = lruHead; ; hashEntryAdr = hashEntryAccess.getLRUNext(hashEntryAdr), listLen++)
                    {
                        // at LRU tail
                        if (hashEntryAdr == 0L)
                            break;
                        lastHashEntryAdr = hashEntryAdr;
                    }

                    if (listLen >= lruListLenTrigger)
                        rehashRequired = true;

                    // hash partition is empty
                    if (lastHashEntryAdr == 0L)
                        continue;

                    long firstBefore = 0L;
                    int i = 0;
                    for (long hashEntryAdr = hashEntryAccess.getLRUPrevious(lastHashEntryAdr); i++ < entriesToRemovePerPartition; hashEntryAdr = hashEntryAccess.getLRUPrevious(hashEntryAdr))
                    {
                        // at LRU head
                        if (hashEntryAdr == 0L)
                            break;
                        firstBefore = hashEntryAdr;
                    }

                    // remove whole partition
                    if (firstBefore == 0L)
                    {
                        startAt = lruHead;
                        hashPartitionAccess.setLRUHead(partAdr, 0L);
                    }
                    else
                    {
                        startAt = hashEntryAccess.getLRUNext(firstBefore);
                        hashEntryAccess.setLRUNext(firstBefore, 0L);
                        hashEntryAccess.setLRUPrevious(startAt, 0L);
                    }

                    // first hash-entry-address to remove in 'startAt' and unlinked from LRU list - can unlock the partition
                }
                finally
                {
                    hashPartitionAccess.unlockPartition(partAdr);
                }

                // remove entries
                long next;
                for (long hashEntryAdr = startAt; hashEntryAdr != 0L; hashEntryAdr = next)
                {
                    next = hashEntryAccess.getLRUNext(hashEntryAdr);

                    entriesRemoved++;

                    hashEntryAccess.lockEntry(hashEntryAdr);
                    blocksFreed += freeBlocks.freeChain(hashEntryAdr);
                    // do NOT unlock - data block might be used elsewhere
                }
            }

            evictionCount.addAndGet(entriesRemoved);

            if (LOGGER.isDebugEnabled())
                LOGGER.debug("Cleanup statistics: removed entries={} blocks recycled={}", entriesRemoved, blocksFreed);

            if (rehashRequired)
                rehashInt();
        }
        finally
        {
            globalLock.set(false);
        }
    }

    public double getFreeSpacePercentage()
    {
        long totalBlockCount = capacity / blockSize;
        int freeBlockCount = calcFreeBlockCount();
        return ((double) freeBlockCount) / totalBlockCount;
    }

    public OHCacheStats extendedStats()
    {
        return new OHCacheStats(stats(), freeBlocks.calcFreeBlockCounts(), calcLruListLengths(),
                                size(), blockSize, capacity, rehashCount.get());
    }

    public CacheStats stats()
    {
        assertNotClosed();

        return new CacheStats(
                             hitCount.get(),
                             missCount.get(),
                             loadSuccessCount.get(),
                             loadExceptionCount.get(),
                             totalLoadTime.get(),
                             evictionCount.get()
        );
    }

    public long size()
    {
        assertNotClosed();

        long sz = 0L;
        for (int p = 0; p < getHashTableSize(); p++)
        {

            long partitionAdr = hashPartitionAccess.lockPartitionForHash(p);
            try
            {
                for (long hashEntryAdr = hashPartitionAccess.getLRUHead(partitionAdr); hashEntryAdr != 0L; hashEntryAdr = hashEntryAccess.getLRUNext(hashEntryAdr))
                    sz++;
            }
            finally
            {
                hashPartitionAccess.unlockPartition(partitionAdr);
            }
        }
        return sz;
    }

    public void invalidateAll()
    {
        assertNotClosed();

        removeAllInt();
    }

    public void invalidateAll(Iterable<?> iterable)
    {
        for (Object o : iterable)
            invalidate(o);
    }

    public void putAll(Map<? extends K, ? extends V> map)
    {
        for (Map.Entry<? extends K, ? extends V> entry : map.entrySet())
            put(entry.getKey(), entry.getValue());
    }

    public ImmutableMap<K, V> getAllPresent(Iterable<?> iterable)
    {
        assertNotClosed();

        ImmutableMap.Builder<K, V> r = ImmutableMap.builder();
        for (Object o : iterable)
        {
            V v = getIfPresent(o);
            if (v != null)
                r.put((K) o, v);
        }
        return r.build();
    }

    public V get(K k, Callable<? extends V> callable) throws ExecutionException
    {
        V v = getIfPresent(k);
        if (v == null)
        {
            long t0 = System.currentTimeMillis();
            try
            {
                v = callable.call();
                loadSuccessCount.incrementAndGet();
            }
            catch (Exception e)
            {
                loadExceptionCount.incrementAndGet();
                throw new ExecutionException(e);
            }
            finally
            {
                totalLoadTime.addAndGet(System.currentTimeMillis() - t0);
            }
            put(k, v);
        }
        return v;
    }

    public Iterator<K> hotN(int n)
    {
        assertNotClosed();

        if (keySerializer == null)
            throw new NullPointerException("no keySerializer configured");

        final int keysPerPartition = (n / getHashTableSize()) + 1;

        return new AbstractIterator<K>()
        {
            public HashEntryAccess.HashEntryCallback cb = new HashEntryAccess.HashEntryCallback()
            {
                void hashEntry(long hashEntryAdr)
                {
                    try
                    {
                        keys.add(keySerializer.deserialize(hashEntryAccess.readKeyFrom(hashEntryAdr)));
                    }
                    catch (IOException e)
                    {
                        throw new IOError(e);
                    }
                }
            };
            private int h;
            private final List<K> keys = new ArrayList<>();
            private Iterator<K> subIter;

            protected K computeNext()
            {
                while (true)
                {
                    if (h == getHashTableSize())
                        return endOfData();

                    if (subIter != null && subIter.hasNext())
                        return subIter.next();
                    keys.clear();

                    assertNotClosed();

                    hashEntryAccess.hotN(h++, cb, keysPerPartition);
                    subIter = keys.iterator();
                }
            }
        };
    }

    public ConcurrentMap<K, V> asMap()
    {
        throw new UnsupportedOperationException();
    }

    private void rehashInt()
    {
        int hashTableSize = getHashTableSize();
        int newHashTableSize = hashTableSize << 1;
        if (newHashTableSize == MAXIMUM_INT)
            return;

        hashPartitionAccess.prepareRehash(newHashTableSize);

        for (int i = 0; i < hashTableSize; i++)
        {
            long curr0 = 0L;
            long curr1 = 0L;

            long partitionAdr = hashPartitionAccess.lockPartitionForHash(i);
            try
            {
                long next;
                for (long hashEntryAdr = hashPartitionAccess.getLRUHead(partitionAdr); hashEntryAdr != 0L; hashEntryAdr = next)
                {
                    next = hashEntryAccess.getLRUNext(hashEntryAdr);

                    int hash = hashEntryAccess.getEntryHash(hashEntryAdr);
                    if ((hash & hashTableSize) == hashTableSize)
                    {
                        hashEntryAccess.setLRUPrevious(hashEntryAdr, curr1);
                        if (curr1 == 0L)
                            hashPartitionAccess.setLRUHeadAlt(hashPartitionAccess.rehashPartitionForHash(hash), hashEntryAdr);
                        else
                            hashEntryAccess.setLRUNext(curr1, hashEntryAdr);
                        curr1 = hashEntryAdr;
                    }
                    else
                    {
                        hashEntryAccess.setLRUPrevious(hashEntryAdr, curr0);
                        if (curr0 == 0L)
                            hashPartitionAccess.setLRUHeadAlt(hashPartitionAccess.rehashPartitionForHash(hash), hashEntryAdr);
                        else
                            hashEntryAccess.setLRUNext(curr0, hashEntryAdr);
                        curr0 = hashEntryAdr;
                    }
                }
                if (curr0 != 0L)
                    hashEntryAccess.setLRUNext(curr0, 0L);
                if (curr1 != 0L)
                    hashEntryAccess.setLRUNext(curr1, 0L);

                hashPartitionAccess.rehashProgress(i);
            }
            finally
            {
                hashPartitionAccess.unlockPartition(partitionAdr);
            }
        }

        hashPartitionAccess.finishRehash();

        rehashCount.incrementAndGet();
    }
}
