/*
 * Copyright 2012 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package io.netty.buffer;

import io.netty.util.internal.LongCounter;
import io.netty.util.internal.PlatformDependent;
import io.netty.util.internal.StringUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.Math.max;

abstract class PoolArena<T> implements PoolArenaMetric {
    /**
     * 是否支持 Unsafe 操作
     */
    static final boolean HAS_UNSAFE = PlatformDependent.hasUnsafe();

    enum SizeClass {
        Tiny,
        Small,
        Normal
    }

    /**
     * {@link #tinySubpagePools} 数组的大小
     *
     * 默认为 32
     */
    static final int numTinySubpagePools = 512 >>> 4;

    /**
     * 所属 PooledByteBufAllocator 对象
     */
    final PooledByteBufAllocator parent;

    /**
     * 满二叉树的高度。默认为 11 。
     */
    private final int maxOrder;
    /**
     * Page 大小，默认 8KB = 8192B
     */
    final int pageSize;
    /**
     * 从 1 开始左移到 {@link #pageSize} 的位数。默认 13 ，1 << 13 = 8192 。
     */
    final int pageShifts;
    /**
     * Chunk 内存块占用大小。默认为 16M = 16 * 1024  。
     */
    final int chunkSize;
    /**
     * 判断分配请求内存是否为 Tiny/Small ，即分配 Subpage 内存块。
     *
     * Used to determine if the requested capacity is equal to or greater than pageSize.
     */
    final int subpageOverflowMask;
    /**
     * {@link #smallSubpagePools} 数组的大小
     *
     * 默认为 23
     */
    final int numSmallSubpagePools;
    /**
     * 对齐基准
     */
    final int directMemoryCacheAlignment;
    /**
     * {@link #directMemoryCacheAlignment} 掩码
     */
    final int directMemoryCacheAlignmentMask;
    /**
     * tiny 类型的 PoolSubpage 数组
     *
     * 数组的每个元素，都是双向链表
     */
    private final PoolSubpage<T>[] tinySubpagePools;
    /**
     * 对齐基准
     */
    private final PoolSubpage<T>[] smallSubpagePools;

    private final PoolChunkList<T> q050;
    private final PoolChunkList<T> q025;
    private final PoolChunkList<T> q000;
    private final PoolChunkList<T> qInit;
    private final PoolChunkList<T> q075;
    private final PoolChunkList<T> q100;

    /**
     * PoolChunkListMetric 数组
     */
    private final List<PoolChunkListMetric> chunkListMetrics;

    /**
     * 分配 Normal 内存块的次数
     * Metrics for allocations and deallocations
     */
    private long allocationsNormal;
    /**
     * 分配 Tiny 内存块的次数
     * We need to use the LongCounter here as this is not guarded via synchronized block.
     */
    private final LongCounter allocationsTiny = PlatformDependent.newLongCounter();
    /**
     * 分配 Small 内存块的次数
     */
    private final LongCounter allocationsSmall = PlatformDependent.newLongCounter();
    /**
     * 分配 Huge 内存块的次数
     */
    private final LongCounter allocationsHuge = PlatformDependent.newLongCounter();
    /**
     * 正在使用中的 Huge 内存块的总共占用字节数
     */
    private final LongCounter activeBytesHuge = PlatformDependent.newLongCounter();

    /**
     * 释放 Tiny 内存块的次数
     */
    private long deallocationsTiny;
    /**
     * 释放 Small 内存块的次数
     */
    private long deallocationsSmall;
    /**
     * 释放 Normal 内存块的次数
     */
    private long deallocationsNormal;

    /**
     * 释放 Huge 内存块的次数
     * We need to use the LongCounter here as this is not guarded via synchronized block.
     */
    private final LongCounter deallocationsHuge = PlatformDependent.newLongCounter();

    /**
     * 该 PoolArena 被多少线程引用的计数器
     * Number of thread caches backed by this arena.
     */
    final AtomicInteger numThreadCaches = new AtomicInteger();

    // TODO: Test if adding padding helps under contention
    //private long pad0, pad1, pad2, pad3, pad4, pad5, pad6, pad7;

    protected PoolArena(PooledByteBufAllocator parent, int pageSize,
          int maxOrder, int pageShifts, int chunkSize, int cacheAlignment) {
        this.parent = parent;
        this.pageSize = pageSize;
        this.maxOrder = maxOrder;
        this.pageShifts = pageShifts;
        this.chunkSize = chunkSize;
        directMemoryCacheAlignment = cacheAlignment;
        directMemoryCacheAlignmentMask = cacheAlignment - 1;
        subpageOverflowMask = ~(pageSize - 1);

        // 初始化 tinySubpagePools 数组
        tinySubpagePools = newSubpagePoolArray(numTinySubpagePools);
        for (int i = 0; i < tinySubpagePools.length; i ++) {
            tinySubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        // 初始化 smallSubpagePools 数组
        numSmallSubpagePools = pageShifts - 9;
        smallSubpagePools = newSubpagePoolArray(numSmallSubpagePools);
        for (int i = 0; i < smallSubpagePools.length; i ++) {
            smallSubpagePools[i] = newSubpagePoolHead(pageSize);
        }

        // 初始化 PoolChunkList
        q100 = new PoolChunkList<T>(this, null, 100, Integer.MAX_VALUE, chunkSize);
        q075 = new PoolChunkList<T>(this, q100, 75, 100, chunkSize);
        q050 = new PoolChunkList<T>(this, q075, 50, 100, chunkSize);
        q025 = new PoolChunkList<T>(this, q050, 25, 75, chunkSize);
        q000 = new PoolChunkList<T>(this, q025, 1, 50, chunkSize);
        qInit = new PoolChunkList<T>(this, q000, Integer.MIN_VALUE, 25, chunkSize);

        q100.prevList(q075);
        q075.prevList(q050);
        q050.prevList(q025);
        q025.prevList(q000);
        q000.prevList(null);
        qInit.prevList(qInit);

        // 创建 PoolChunkListMetric 数组
        List<PoolChunkListMetric> metrics = new ArrayList<PoolChunkListMetric>(6);
        metrics.add(qInit);
        metrics.add(q000);
        metrics.add(q025);
        metrics.add(q050);
        metrics.add(q075);
        metrics.add(q100);
        chunkListMetrics = Collections.unmodifiableList(metrics);
    }

    private PoolSubpage<T> newSubpagePoolHead(int pageSize) {
        PoolSubpage<T> head = new PoolSubpage<T>(pageSize);
        head.prev = head;
        head.next = head;
        return head;
    }

    @SuppressWarnings("unchecked")
    private PoolSubpage<T>[] newSubpagePoolArray(int size) {
        return new PoolSubpage[size];
    }

    abstract boolean isDirect();

    PooledByteBuf<T> allocate(PoolThreadCache cache, int reqCapacity, int maxCapacity) {
        PooledByteBuf<T> buf = newByteBuf(maxCapacity);
        allocate(cache, buf, reqCapacity);
        return buf;
    }

    static int tinyIdx(int normCapacity) {
        return normCapacity >>> 4;
    }

    static int smallIdx(int normCapacity) {
        int tableIdx = 0;
        int i = normCapacity >>> 10;
        while (i != 0) {
            i >>>= 1;
            tableIdx ++;
        }
        return tableIdx;
    }

    // capacity < pageSize
    boolean isTinyOrSmall(int normCapacity) {
        return (normCapacity & subpageOverflowMask) == 0;
    }

    // normCapacity < 512
    static boolean isTiny(int normCapacity) {
        return (normCapacity & 0xFFFFFE00) == 0;
    }

    private void allocate(PoolThreadCache cache, PooledByteBuf<T> buf, final int reqCapacity) {
        // 标准化请求分配的容量
        final int normCapacity = normalizeCapacity(reqCapacity);

        // PoolSubpage 的情况  即 Tiny 或者 Small 规格
        if (isTinyOrSmall(normCapacity)) { // capacity < pageSize
            int tableIdx;
            PoolSubpage<T>[] table;
            boolean tiny = isTiny(normCapacity);
            // Tiny 规格
            if (tiny) { // < 512

                // 缓存上存在 直接分配给 PooledByteBuf
                if (cache.allocateTiny(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }

                // 获取该规格内存在 SubpagePools 数组中的索引
                tableIdx = tinyIdx(normCapacity);
                // SubpagePools 数组
                table = tinySubpagePools;

                // Small 规格
            } else {
                // 缓存上存在 直接分配给 PooledByteBuf
                if (cache.allocateSmall(this, buf, reqCapacity, normCapacity)) {
                    // was able to allocate out of the cache so move on
                    return;
                }
                tableIdx = smallIdx(normCapacity);
                table = smallSubpagePools;
            }

            // 获取该规格在 SubpagePools 数组中的 head 节点
            final PoolSubpage<T> head = table[tableIdx];

            /**
             * Synchronize on the head. This is needed as {@link PoolChunk#allocateSubpage(int)} and
             * {@link PoolChunk#free(long)} may modify the doubly linked list as well.
             */
            synchronized (head) {
                // 获取头结点的下一个节点
                final PoolSubpage<T> s = head.next;

                if (s != head) {
                    // 断言这个规格是对的 并且还没有被销毁
                    assert s.doNotDestroy && s.elemSize == normCapacity;
                    // 直接让 PoolSubpage 分配出一块内存
                    long handle = s.allocate();
                    // 断言成功分配内存
                    assert handle >= 0;
                    // 把这个内存初始化给 PooledByteBuf
                    s.chunk.initBufWithSubpage(buf, handle, reqCapacity);
                    // 分配成功 计数+1
                    incTinySmallAllocation(tiny);
                    return;
                }
            }

            // 在 PoolSubpage 链表中，分配不到 SubPage 内存块，
            // 所以申请 Normal Page 内存块。实际上，只占用其中一块 SubPage 内存块
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
            }
            // 计数
            incTinySmallAllocation(tiny);
            return;
        }

        // normal 规格
        if (normCapacity <= chunkSize) {
            if (cache.allocateNormal(this, buf, reqCapacity, normCapacity)) {
                // was able to allocate out of the cache so move on
                return;
            }
            synchronized (this) {
                allocateNormal(buf, reqCapacity, normCapacity);
                ++allocationsNormal;
            }

            // Huge 规格
        } else {
            // Huge allocations are never served via the cache so just call allocateHuge
            allocateHuge(buf, reqCapacity);
        }
    }

    /**
     * 分配一个 normal 规格的内存
     */
    // Method must be called inside synchronized(this) { ... } block
    private void allocateNormal(PooledByteBuf<T> buf, int reqCapacity, int normCapacity) {

        // 按照优先级，从多个 ChunkList 中，分配 Normal Page 内存块。如果有一分配成功，返回
        if (q050.allocate(buf, reqCapacity, normCapacity) || q025.allocate(buf, reqCapacity, normCapacity) ||
            q000.allocate(buf, reqCapacity, normCapacity) || qInit.allocate(buf, reqCapacity, normCapacity) ||
            q075.allocate(buf, reqCapacity, normCapacity)) {
            return;
        }

        // Add a new chunk.
        // 新建 Chunk 内存块
        PoolChunk<T> c = newChunk(pageSize, maxOrder, pageShifts, chunkSize);
        // 在这个 Chunk 中 分配一个 normCapacity 大小的内存
        long handle = c.allocate(normCapacity);
        assert handle > 0;
        // 把这块内存初始化到 PooledByteBuf 中
        c.initBuf(buf, handle, reqCapacity);
        // 把这个 Chunk 添加到 PoolChunkList 中
        qInit.add(c);
    }

    private void incTinySmallAllocation(boolean tiny) {
        if (tiny) {
            allocationsTiny.increment();
        } else {
            allocationsSmall.increment();
        }
    }

    private void allocateHuge(PooledByteBuf<T> buf, int reqCapacity) {
        // 新建 Chunk 内存块，它是 unpooled 的
        PoolChunk<T> chunk = newUnpooledChunk(reqCapacity);
        // 增加 activeBytesHuge
        activeBytesHuge.add(chunk.chunkSize());
        // 初始化 Huge 内存块到 PooledByteBuf 对象中
        buf.initUnpooled(chunk, reqCapacity);
        // 增加 allocationsHuge
        allocationsHuge.increment();
    }

    void free(PoolChunk<T> chunk, long handle, int normCapacity, PoolThreadCache cache) {
        // 非池化的 直接释放
        if (chunk.unpooled) {
            int size = chunk.chunkSize();
            // 直接销毁 Chunk 内存块，因为占用空间较大
            destroyChunk(chunk);
            // 减少 activeBytesHuge 计数
            activeBytesHuge.add(-size);
            // 减少 deallocationsHuge 计数
            deallocationsHuge.increment();
        } else {
            // 计算内存块的规格
            SizeClass sizeClass = sizeClass(normCapacity);
            // 添加内存块到 PoolThreadCache 缓存
            if (cache != null && cache.add(this, chunk, handle, normCapacity, sizeClass)) {
                // cached so not free it.
                return;
            }
            // todo  why 走到这里
            // 释放 Page / Subpage 内存块回 Chunk 中
            freeChunk(chunk, handle, sizeClass);
        }
    }

    private SizeClass sizeClass(int normCapacity) {
        if (!isTinyOrSmall(normCapacity)) {
            return SizeClass.Normal;
        }
        return isTiny(normCapacity) ? SizeClass.Tiny : SizeClass.Small;
    }

    void freeChunk(PoolChunk<T> chunk, long handle, SizeClass sizeClass) {
        final boolean destroyChunk;
        synchronized (this) {

            // 所属规格的释放次数+1
            switch (sizeClass) {
            case Normal:
                ++deallocationsNormal;
                break;
            case Small:
                ++deallocationsSmall;
                break;
            case Tiny:
                ++deallocationsTiny;
                break;
            default:
                throw new Error();
            }

            // PoolChunkList释放掉指定位置的 page / SubPage
            destroyChunk = !chunk.parent.free(chunk, handle);
        }

        // PoolChunkList 如果没有释放成功
        // 意味着 Chunk 中不存在在使用的 Page / SubPage 内存块。也就是说，内存使用率为 0 ，所以销毁 Chunk
        if (destroyChunk) {
            // destroyChunk not need to be called while holding the synchronized lock.
            destroyChunk(chunk);
        }
    }

    /**
     * 找到请求分配的 Subpage 类型的内存的链表的头节点
     */
    PoolSubpage<T> findSubpagePoolHead(int elemSize) {
        int tableIdx;
        PoolSubpage<T>[] table;
        if (isTiny(elemSize)) { // < 512
            // 实际上，就是 `#tinyIdx(int normCapacity)` 方法
            tableIdx = elemSize >>> 4;
            // 获得 table
            table = tinySubpagePools;
        } else {
            // 实际上，就是 `#smallIdx(int normCapacity)` 方法
            tableIdx = 0;
            elemSize >>>= 10;
            while (elemSize != 0) {
                elemSize >>>= 1;
                tableIdx ++;
            }
            // 获得 table
            table = smallSubpagePools;
        }

        return table[tableIdx];
    }

    /**
     * 标准化请求分配的容量
     * 比如 传入的 reqCapacity 是 18B  就会把它转成32B
     *     传入的是 9k 就会把它转成 16k
     */
    int normalizeCapacity(int reqCapacity) {
        if (reqCapacity < 0) {
            throw new IllegalArgumentException("capacity: " + reqCapacity + " (expected: 0+)");
        }

        // Huge 内存类型，直接使用 reqCapacity ，无需进行标准化。
        if (reqCapacity >= chunkSize) {
            return directMemoryCacheAlignment == 0 ? reqCapacity : alignCapacity(reqCapacity);
        }

        // 非 tiny 内存类型 即 small normal 类型
        if (!isTiny(reqCapacity)) { // >= 512
            // Doubled
            // 转换成接近于两倍的容量
            int normalizedCapacity = reqCapacity;
            normalizedCapacity --;
            normalizedCapacity |= normalizedCapacity >>>  1;
            normalizedCapacity |= normalizedCapacity >>>  2;
            normalizedCapacity |= normalizedCapacity >>>  4;
            normalizedCapacity |= normalizedCapacity >>>  8;
            normalizedCapacity |= normalizedCapacity >>> 16;
            normalizedCapacity ++;

            if (normalizedCapacity < 0) {
                normalizedCapacity >>>= 1;
            }
            assert directMemoryCacheAlignment == 0 || (normalizedCapacity & directMemoryCacheAlignmentMask) == 0;

            return normalizedCapacity;
        }

        // tiny 内存类型

        if (directMemoryCacheAlignment > 0) {
            return alignCapacity(reqCapacity);
        }

        // 补齐成 16 的倍数
        // Quantum-spaced
        if ((reqCapacity & 15) == 0) {
            return reqCapacity;
        }

        return (reqCapacity & ~15) + 16;
    }

    int alignCapacity(int reqCapacity) {
        // 获得 delta
        int delta = reqCapacity & directMemoryCacheAlignmentMask;
        // 补齐 directMemoryCacheAlignment ，并减去 delta
        return delta == 0 ? reqCapacity : reqCapacity + directMemoryCacheAlignment - delta;
    }

    void reallocate(PooledByteBuf<T> buf, int newCapacity, boolean freeOldMemory) {
        if (newCapacity < 0 || newCapacity > buf.maxCapacity()) {
            throw new IllegalArgumentException("newCapacity: " + newCapacity);
        }

        // 不需要扩容或者缩容
        int oldCapacity = buf.length;
        if (oldCapacity == newCapacity) {
            return;
        }

        // 记录老的内存块的信息
        PoolChunk<T> oldChunk = buf.chunk;
        long oldHandle = buf.handle;
        T oldMemory = buf.memory;
        int oldOffset = buf.offset;
        int oldMaxLength = buf.maxLength;
        // 记录读写索引
        int readerIndex = buf.readerIndex();
        int writerIndex = buf.writerIndex();

        // 分配新的内存块给 PooledByteBuf 对象
        allocate(parent.threadCache(), buf, newCapacity);

        // 扩容
        if (newCapacity > oldCapacity) {
            // 将老的内存块的数据，复制到新的内存块中
            memoryCopy(
                    oldMemory, oldOffset,
                    buf.memory, buf.offset, oldCapacity);

            // 缩容
        } else if (newCapacity < oldCapacity) {

            // 有部分数据未读取完
            if (readerIndex < newCapacity) {

                // 如果写索引大写容量 就把写索引置为容量 意味着丢弃后面的数据
                if (writerIndex > newCapacity) {
                    writerIndex = newCapacity;
                }
                memoryCopy(
                        oldMemory, oldOffset + readerIndex,
                        buf.memory, buf.offset + readerIndex, writerIndex - readerIndex);

                // 其他情况 直接丢弃数据
            } else {
                readerIndex = writerIndex = newCapacity;
            }
        }

        // 更新读写索引
        buf.setIndex(readerIndex, writerIndex);

        // 释放老内存块的数据
        if (freeOldMemory) {
            free(oldChunk, oldHandle, oldMaxLength, buf.cache);
        }
    }

    @Override
    public int numThreadCaches() {
        return numThreadCaches.get();
    }

    @Override
    public int numTinySubpages() {
        return tinySubpagePools.length;
    }

    @Override
    public int numSmallSubpages() {
        return smallSubpagePools.length;
    }

    @Override
    public int numChunkLists() {
        return chunkListMetrics.size();
    }

    @Override
    public List<PoolSubpageMetric> tinySubpages() {
        return subPageMetricList(tinySubpagePools);
    }

    @Override
    public List<PoolSubpageMetric> smallSubpages() {
        return subPageMetricList(smallSubpagePools);
    }

    @Override
    public List<PoolChunkListMetric> chunkLists() {
        return chunkListMetrics;
    }

    private static List<PoolSubpageMetric> subPageMetricList(PoolSubpage<?>[] pages) {
        List<PoolSubpageMetric> metrics = new ArrayList<PoolSubpageMetric>();
        for (PoolSubpage<?> head : pages) {
            if (head.next == head) {
                continue;
            }
            PoolSubpage<?> s = head.next;
            for (;;) {
                metrics.add(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
        return metrics;
    }

    @Override
    public long numAllocations() {
        final long allocsNormal;
        synchronized (this) {
            allocsNormal = allocationsNormal;
        }
        return allocationsTiny.value() + allocationsSmall.value() + allocsNormal + allocationsHuge.value();
    }

    @Override
    public long numTinyAllocations() {
        return allocationsTiny.value();
    }

    @Override
    public long numSmallAllocations() {
        return allocationsSmall.value();
    }

    @Override
    public synchronized long numNormalAllocations() {
        return allocationsNormal;
    }

    @Override
    public long numDeallocations() {
        final long deallocs;
        synchronized (this) {
            deallocs = deallocationsTiny + deallocationsSmall + deallocationsNormal;
        }
        return deallocs + deallocationsHuge.value();
    }

    @Override
    public synchronized long numTinyDeallocations() {
        return deallocationsTiny;
    }

    @Override
    public synchronized long numSmallDeallocations() {
        return deallocationsSmall;
    }

    @Override
    public synchronized long numNormalDeallocations() {
        return deallocationsNormal;
    }

    @Override
    public long numHugeAllocations() {
        return allocationsHuge.value();
    }

    @Override
    public long numHugeDeallocations() {
        return deallocationsHuge.value();
    }

    @Override
    public  long numActiveAllocations() {
        long val = allocationsTiny.value() + allocationsSmall.value() + allocationsHuge.value()
                - deallocationsHuge.value();
        synchronized (this) {
            val += allocationsNormal - (deallocationsTiny + deallocationsSmall + deallocationsNormal);
        }
        return max(val, 0);
    }

    @Override
    public long numActiveTinyAllocations() {
        return max(numTinyAllocations() - numTinyDeallocations(), 0);
    }

    @Override
    public long numActiveSmallAllocations() {
        return max(numSmallAllocations() - numSmallDeallocations(), 0);
    }

    @Override
    public long numActiveNormalAllocations() {
        final long val;
        synchronized (this) {
            val = allocationsNormal - deallocationsNormal;
        }
        return max(val, 0);
    }

    @Override
    public long numActiveHugeAllocations() {
        return max(numHugeAllocations() - numHugeDeallocations(), 0);
    }

    @Override
    public long numActiveBytes() {
        long val = activeBytesHuge.value();
        synchronized (this) {
            for (int i = 0; i < chunkListMetrics.size(); i++) {
                for (PoolChunkMetric m: chunkListMetrics.get(i)) {
                    val += m.chunkSize();
                }
            }
        }
        return max(0, val);
    }

    protected abstract PoolChunk<T> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize);
    protected abstract PoolChunk<T> newUnpooledChunk(int capacity);
    protected abstract PooledByteBuf<T> newByteBuf(int maxCapacity);
    protected abstract void memoryCopy(T src, int srcOffset, T dst, int dstOffset, int length);
    protected abstract void destroyChunk(PoolChunk<T> chunk);

    @Override
    public synchronized String toString() {
        StringBuilder buf = new StringBuilder()
            .append("Chunk(s) at 0~25%:")
            .append(StringUtil.NEWLINE)
            .append(qInit)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 0~50%:")
            .append(StringUtil.NEWLINE)
            .append(q000)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 25~75%:")
            .append(StringUtil.NEWLINE)
            .append(q025)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 50~100%:")
            .append(StringUtil.NEWLINE)
            .append(q050)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 75~100%:")
            .append(StringUtil.NEWLINE)
            .append(q075)
            .append(StringUtil.NEWLINE)
            .append("Chunk(s) at 100%:")
            .append(StringUtil.NEWLINE)
            .append(q100)
            .append(StringUtil.NEWLINE)
            .append("tiny subpages:");
        appendPoolSubPages(buf, tinySubpagePools);
        buf.append(StringUtil.NEWLINE)
           .append("small subpages:");
        appendPoolSubPages(buf, smallSubpagePools);
        buf.append(StringUtil.NEWLINE);

        return buf.toString();
    }

    private static void appendPoolSubPages(StringBuilder buf, PoolSubpage<?>[] subpages) {
        for (int i = 0; i < subpages.length; i ++) {
            PoolSubpage<?> head = subpages[i];
            if (head.next == head) {
                continue;
            }

            buf.append(StringUtil.NEWLINE)
                    .append(i)
                    .append(": ");
            PoolSubpage<?> s = head.next;
            for (;;) {
                buf.append(s);
                s = s.next;
                if (s == head) {
                    break;
                }
            }
        }
    }

    /**
     *  该对象在GC 回收前会调用这个方法
     */
    @Override
    protected final void finalize() throws Throwable {
        try {
            super.finalize();
        } finally {
            // 清理 tiny Subpage 们
            destroyPoolSubPages(smallSubpagePools);
            // 清理 small Subpage 们
            destroyPoolSubPages(tinySubpagePools);
            // 清理 ChunkList 们
            destroyPoolChunkLists(qInit, q000, q025, q050, q075, q100);
        }
    }

    private static void destroyPoolSubPages(PoolSubpage<?>[] pages) {
        for (PoolSubpage<?> page : pages) {
            page.destroy();
        }
    }

    private void destroyPoolChunkLists(PoolChunkList<T>... chunkLists) {
        for (PoolChunkList<T> chunkList: chunkLists) {
            chunkList.destroy(this);
        }
    }

    static final class HeapArena extends PoolArena<byte[]> {

        HeapArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        private static byte[] newByteArray(int size) {
            return PlatformDependent.allocateUninitializedArray(size);
        }

        @Override
        boolean isDirect() {
            return false;
        }

        @Override
        protected PoolChunk<byte[]> newChunk(int pageSize, int maxOrder, int pageShifts, int chunkSize) {
            return new PoolChunk<byte[]>(this, newByteArray(chunkSize), pageSize, maxOrder, pageShifts, chunkSize, 0);
        }

        @Override
        protected PoolChunk<byte[]> newUnpooledChunk(int capacity) {
            return new PoolChunk<byte[]>(this, newByteArray(capacity), capacity, 0);
        }

        @Override
        protected void destroyChunk(PoolChunk<byte[]> chunk) {
            // Rely on GC.
        }

        @Override
        protected PooledByteBuf<byte[]> newByteBuf(int maxCapacity) {
            return HAS_UNSAFE ? PooledUnsafeHeapByteBuf.newUnsafeInstance(maxCapacity)
                    : PooledHeapByteBuf.newInstance(maxCapacity);
        }

        @Override
        protected void memoryCopy(byte[] src, int srcOffset, byte[] dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            System.arraycopy(src, srcOffset, dst, dstOffset, length);
        }
    }

    static final class DirectArena extends PoolArena<ByteBuffer> {

        DirectArena(PooledByteBufAllocator parent, int pageSize, int maxOrder,
                int pageShifts, int chunkSize, int directMemoryCacheAlignment) {
            super(parent, pageSize, maxOrder, pageShifts, chunkSize,
                    directMemoryCacheAlignment);
        }

        @Override
        boolean isDirect() {
            return true;
        }

        private int offsetCacheLine(ByteBuffer memory) {
            // We can only calculate the offset if Unsafe is present as otherwise directBufferAddress(...) will
            // throw an NPE.
            return HAS_UNSAFE ?
                    (int) (PlatformDependent.directBufferAddress(memory) & directMemoryCacheAlignmentMask) : 0;
        }

        @Override
        protected PoolChunk<ByteBuffer> newChunk(int pageSize, int maxOrder,
                int pageShifts, int chunkSize) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(chunkSize), pageSize, maxOrder,
                        pageShifts, chunkSize, 0);
            }
            final ByteBuffer memory = allocateDirect(chunkSize
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, pageSize,
                    maxOrder, pageShifts, chunkSize,
                    offsetCacheLine(memory));
        }

        @Override
        protected PoolChunk<ByteBuffer> newUnpooledChunk(int capacity) {
            if (directMemoryCacheAlignment == 0) {
                return new PoolChunk<ByteBuffer>(this,
                        allocateDirect(capacity), capacity, 0);
            }
            final ByteBuffer memory = allocateDirect(capacity
                    + directMemoryCacheAlignment);
            return new PoolChunk<ByteBuffer>(this, memory, capacity,
                    offsetCacheLine(memory));
        }

        private static ByteBuffer allocateDirect(int capacity) {
            return PlatformDependent.useDirectBufferNoCleaner() ?
                    PlatformDependent.allocateDirectNoCleaner(capacity) : ByteBuffer.allocateDirect(capacity);
        }

        @Override
        protected void destroyChunk(PoolChunk<ByteBuffer> chunk) {
            if (PlatformDependent.useDirectBufferNoCleaner()) {
                PlatformDependent.freeDirectNoCleaner(chunk.memory);
            } else {
                PlatformDependent.freeDirectBuffer(chunk.memory);
            }
        }

        @Override
        protected PooledByteBuf<ByteBuffer> newByteBuf(int maxCapacity) {
            if (HAS_UNSAFE) {
                return PooledUnsafeDirectByteBuf.newInstance(maxCapacity);
            } else {
                return PooledDirectByteBuf.newInstance(maxCapacity);
            }
        }

        @Override
        protected void memoryCopy(ByteBuffer src, int srcOffset, ByteBuffer dst, int dstOffset, int length) {
            if (length == 0) {
                return;
            }

            if (HAS_UNSAFE) {
                PlatformDependent.copyMemory(
                        PlatformDependent.directBufferAddress(src) + srcOffset,
                        PlatformDependent.directBufferAddress(dst) + dstOffset, length);
            } else {
                // We must duplicate the NIO buffers because they may be accessed by other Netty buffers.
                src = src.duplicate();
                dst = dst.duplicate();
                src.position(srcOffset).limit(srcOffset + length);
                dst.position(dstOffset);
                dst.put(src);
            }
        }
    }
}
