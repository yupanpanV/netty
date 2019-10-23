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

/**
 * Implementations are responsible to allocate buffers. Implementations of this interface are expected to be
 * thread-safe.
 */
public interface ByteBufAllocator {

    /**
     *  分为 pooled 和unpooled
     */
    ByteBufAllocator DEFAULT = ByteBufUtil.DEFAULT_ALLOCATOR;

    /**
     * 分配一个 ByteBuf 默认容量 Integer.MAX_VALUE
     */
    ByteBuf buffer();

    /**
     *  分配一个指定容量的 ByteBuf
     */
    ByteBuf buffer(int initialCapacity);

    /**
     * 分配一个指定容量和最大容量的 ByteBuf
     */
    ByteBuf buffer(int initialCapacity, int maxCapacity);

    /**
     * 倾向于创建一个 direct buffer
     */
    ByteBuf ioBuffer();

    /**
     * 倾向于创建一个带容量的 direct buffer
     */
    ByteBuf ioBuffer(int initialCapacity);

    /**
     * 倾向于创建一个带容量和最大容量的 direct buffer
     */
    ByteBuf ioBuffer(int initialCapacity, int maxCapacity);

    /**
     * 创建一个堆buffer
     */
    ByteBuf heapBuffer();

    /**
     * 创建一个带容量的堆buffer
     */
    ByteBuf heapBuffer(int initialCapacity);

    /**
     * 创建一个带容量和最大容量的堆buffer
     */
    ByteBuf heapBuffer(int initialCapacity, int maxCapacity);

    /**
     * 创建一个 directBuffer
     */
    ByteBuf directBuffer();

    /**
     * 创建一个带容量的 directBuffer
     */
    ByteBuf directBuffer(int initialCapacity);

    /**
     * 创建一个带容量和最大容量的 directBuffer
     */
    ByteBuf directBuffer(int initialCapacity, int maxCapacity);

    /**
     * 创建一个混合 directBuffer
     */
    CompositeByteBuf compositeBuffer();

    /**
     * 创建一个带容量的 混合Buffer
     */
    CompositeByteBuf compositeBuffer(int maxNumComponents);

    /**
     * Allocate a heap {@link CompositeByteBuf}.
     */
    CompositeByteBuf compositeHeapBuffer();

    /**
     * Allocate a heap {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     */
    CompositeByteBuf compositeHeapBuffer(int maxNumComponents);

    /**
     * Allocate a direct {@link CompositeByteBuf}.
     */
    CompositeByteBuf compositeDirectBuffer();

    /**
     * Allocate a direct {@link CompositeByteBuf} with the given maximum number of components that can be stored in it.
     */
    CompositeByteBuf compositeDirectBuffer(int maxNumComponents);

    /**
     * 是否是池化的的 DirectBuffer
     */
    boolean isDirectBufferPooled();

    /**
     *  计算新容量
     */
    int calculateNewCapacity(int minNewCapacity, int maxCapacity);
 }
