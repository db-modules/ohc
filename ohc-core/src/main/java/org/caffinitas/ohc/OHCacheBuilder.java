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

public class OHCacheBuilder<K, V>
{
    private int segmentCount;
    private int hashTableSize;
    private long capacity = 64L * 1024L * 1024L;
    private CacheSerializer<K> keySerializer;
    private CacheSerializer<V> valueSerializer;
    private int entriesPerSegmentTrigger = 10;
    private double cleanUpTriggerMinFree = -1d;
    private boolean statisticsEnabled;

    private OHCacheBuilder()
    {
    }

    public static <K, V> OHCacheBuilder<K, V> newBuilder()
    {
        return new OHCacheBuilder<>();
    }

    public OHCache<K, V> build()
    {
        return new SegmentedCacheImpl<>(this);
    }

    public int getHashTableSize()
    {
        return hashTableSize;
    }

    public OHCacheBuilder<K, V> hashTableSize(int hashTableSize)
    {
        this.hashTableSize = hashTableSize;
        return this;
    }

    public long getCapacity()
    {
        return capacity;
    }

    public OHCacheBuilder<K, V> capacity(long capacity)
    {
        this.capacity = capacity;
        return this;
    }

    public CacheSerializer<K> getKeySerializer()
    {
        return keySerializer;
    }

    public OHCacheBuilder<K, V> keySerializer(CacheSerializer<K> keySerializer)
    {
        this.keySerializer = keySerializer;
        return this;
    }

    public CacheSerializer<V> getValueSerializer()
    {
        return valueSerializer;
    }

    public OHCacheBuilder<K, V> valueSerializer(CacheSerializer<V> valueSerializer)
    {
        this.valueSerializer = valueSerializer;
        return this;
    }

    public int getEntriesPerSegmentTrigger()
    {
        return entriesPerSegmentTrigger;
    }

    public OHCacheBuilder<K, V> entriesPerSegmentTrigger(int entriesPerBucketTrigger)
    {
        this.entriesPerSegmentTrigger = entriesPerBucketTrigger;
        return this;
    }

    public double getCleanUpTriggerMinFree()
    {
        return cleanUpTriggerMinFree;
    }

    public OHCacheBuilder<K, V> cleanUpTriggerMinFree(double cleanUpTrigger)
    {
        this.cleanUpTriggerMinFree = cleanUpTrigger;
        return this;
    }

    public boolean isStatisticsEnabled()
    {
        return statisticsEnabled;
    }

    public OHCacheBuilder<K, V> statisticsEnabled(boolean statisticsEnable)
    {
        this.statisticsEnabled = statisticsEnable;
        return this;
    }

    public int getSegmentCount()
    {
        return segmentCount;
    }

    public OHCacheBuilder<K, V> segmentCount(int segmentCount)
    {
        this.segmentCount = segmentCount;
        return this;
    }
}