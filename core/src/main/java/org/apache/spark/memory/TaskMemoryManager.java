/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.memory;

import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.nio.channels.ClosedByInterruptException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.google.common.annotations.VisibleForTesting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.unsafe.memory.MemoryBlock;
import org.apache.spark.util.Utils;

/**
 * 给一个(only one???)单独的任务管理内存分配
 * Manages the memory allocated by an individual task.
 * <p>
 * Most of the complexity in this class deals with encoding of off-heap addresses into 64-bit longs.
 * In off-heap mode, memory can be directly addressed with 64-bit longs. In on-heap mode, memory is
 * addressed by the combination of a base Object reference and a 64-bit offset within that object.
 * This is a problem when we want to store pointers to data structures inside of other structures,
 * such as record pointers inside hashmaps or sorting buffers. Even if we decided to use 128 bits
 * to address memory, we can't just store the address of the base object since it's not guaranteed
 * to remain stable as the heap gets reorganized due to GC.
 * <p>
 * Instead, we use the following approach to encode record pointers in 64-bit longs: for off-heap
 * mode, just store the raw address, and for on-heap mode use the upper 13 bits of the address to
 * store a "page number" and the lower 51 bits to store an offset within this page. These page
 * numbers are used to index into a "page table" array inside of the MemoryManager in order to
 * retrieve the base object.
 * <p>
 * This allows us to address 8192 pages. In on-heap mode, the maximum page size is limited by the
 * maximum size of a long[] array, allowing us to address 8192 * (2^31 - 1) * 8 bytes, which is
 * approximately 140 terabytes of memory.
 */
public class TaskMemoryManager {

  private static final Logger logger = LoggerFactory.getLogger(TaskMemoryManager.class);

  /** The number of bits used to address the page table. */
  private static final int PAGE_NUMBER_BITS = 13;

  /** The number of bits used to encode offsets in data pages. */
  @VisibleForTesting
  static final int OFFSET_BITS = 64 - PAGE_NUMBER_BITS;  // 51

  // pages总数：2^13 = 8192
  /** The number of entries in the page table. */
  private static final int PAGE_TABLE_SIZE = 1 << PAGE_NUMBER_BITS;

  /**
   * 一个page最多能存储的数据字节大小。理论上，最大的地址是1 << OFFSET_BITS。但是，堆内内存分配器的最大页面size受
   * 到long[]数组所能最大存储的数据的大小的限制，该大小正是(2^31 - 1) * 8，大约140t的内存大小。
   * Maximum supported data page size (in bytes). In principle, the maximum addressable page size is
   * (1L &lt;&lt; OFFSET_BITS) bytes, which is 2+ petabytes. However, the on-heap allocator's
   * maximum page size is limited by the maximum amount of data that can be stored in a long[]
   * array, which is (2^31 - 1) * 8 bytes (or about 17 gigabytes). Therefore, we cap this at 17
   * gigabytes.
   */
  public static final long MAXIMUM_PAGE_SIZE_BYTES = ((1L << 31) - 1) * 8L;

  /** Bit mask for the lower 51 bits of a long. */
  private static final long MASK_LONG_LOWER_51_BITS = 0x7FFFFFFFFFFFFL;

  /**
   * Similar to an operating system's page table, this array maps page numbers into base object
   * pointers, allowing us to translate between the hashtable's internal 64-bit address
   * representation and the baseObject+offset representation which we use to support both in- and
   * off-heap addresses. When using an off-heap allocator, every entry in this map will be `null`.
   * When using an in-heap allocator, the entries in this map will point to pages' base objects.
   * Entries are added to this map as new data pages are allocated.
   */
  private final MemoryBlock[] pageTable = new MemoryBlock[PAGE_TABLE_SIZE];

  /**
   * Bitmap for tracking free pages.
   */
  private final BitSet allocatedPages = new BitSet(PAGE_TABLE_SIZE);

  private final MemoryManager memoryManager;

  // 是否说明一个TaskMemoryManager只对应一个task???
  private final long taskAttemptId;

  /**
   * 哈??? 啥意思???
   * Tracks whether we're in-heap or off-heap. For off-heap, we short-circuit most of these methods
   * without doing any masking or lookups. Since this branching should be well-predicted by the JIT,
   * this extra layer of indirection / abstraction hopefully shouldn't be too expensive.
   */
  final MemoryMode tungstenMemoryMode;

  /**
   * ???????
   * memory consumers和TAskMemoryManager是什么关系???
   * 一个Task对应多个memory consumers???
   * Tracks spillable memory consumers.
   */
  @GuardedBy("this")
  private final HashSet<MemoryConsumer> consumers;

  /**
   * The amount of memory that is acquired but not used.
   */
  private volatile long acquiredButNotUsed = 0L;

  /**
   * Construct a new TaskMemoryManager.
   */
  public TaskMemoryManager(MemoryManager memoryManager, long taskAttemptId) {
    // 默认为ON_HEAP
    this.tungstenMemoryMode = memoryManager.tungstenMemoryMode();
    this.memoryManager = memoryManager;
    this.taskAttemptId = taskAttemptId;
    this.consumers = new HashSet<>();
  }

  /**
   * Acquire N bytes of memory for a consumer. If there is no enough memory, it will call
   * spill() of consumers to release more memory.
   *
   * @return number of bytes successfully granted (<= N).
   */
  public long acquireExecutionMemory(long required, MemoryConsumer consumer) {
    assert(required >= 0);
    assert(consumer != null);
    MemoryMode mode = consumer.getMode();
    // 哈???啥意思???
    // If we are allocating Tungsten pages off-heap and receive a request to allocate on-heap
    // memory here, then it may not make sense to spill since that would only end up freeing
    // off-heap memory. This is subject to change, though, so it may be risky to make this
    // optimization now in case we forget to undo it late when making changes.
    synchronized (this) {
      // 首先，向execution pool申请预期required大小的内存
      long got = memoryManager.acquireExecutionMemory(required, taskAttemptId, mode);

      // 首先尝试从其它MemoryConsumers那里释放内存，这样，我们就可以减少执行spilling的频率，
      // 以避免产生过多的spilled文件。
      // Try to release memory from other consumers first, then we can reduce the frequency of
      // spilling, avoid to have too many spilled files.
      if (got < required) { // 如果实际申请到的内存小于预期获取的内存(说明内存不够用咯，这时就要先尝试
                            // 让其它MemoryConsumers通过执行spill来释放一些内存)
        // Call spill() on other consumers to release memory
        // Sort the consumers according their memory usage. So we avoid spilling the same consumer
        // which is just spilled in last few times and re-spilling on it will produce many small
        // spill files.
        // 使用TreeMap的好处是???排序???
        TreeMap<Long, List<MemoryConsumer>> sortedConsumers = new TreeMap<>();
        // 有这么多consumer吗???还是没有理清TaskMemoryManager和consumer的关系
        for (MemoryConsumer c: consumers) {
          if (c != consumer && c.getUsed() > 0 && c.getMode() == mode) {
            long key = c.getUsed();
            List<MemoryConsumer> list =
                    // 如果key没有在sortedConsumers，则通过第二个参数，为其创建对应的value。
                    // 在这里，对应的value是一个ArrayList。反之，如果key已经存在，则将其对应的
                    // list返回。并将该consumer添加到list中去。也就是说，sortedConsumers把
                    // 内存使用量相同的consumers都添加到了同一个队列中去。
                sortedConsumers.computeIfAbsent(key, k -> new ArrayList<>(1));
            list.add(c);
          }
        }
        while (!sortedConsumers.isEmpty()) {
          // 获取那些使用内存大于剩下还需要申请的内存大小的使用内存最小的MemoryConsumers
          // ceil(n): 获取大于等于n的最小整数。例如，ceil(5.1) = 6;
          // ceilingEntry(key)同理。
          // Get the consumer using the least memory more than the remaining required memory.
          Map.Entry<Long, List<MemoryConsumer>> currentEntry =
            sortedConsumers.ceilingEntry(required - got);
          // 没有一个consumer使用的内存大于还需要申请的内存大小，则获取使用内存最多的那些consumers
          // No consumer has used memory more than the remaining required memory.
          // Get the consumer of largest used memory.
          if (currentEntry == null) {
            // 所以TreeMap是有序的，因为它获取了最后一个entry，它的key最大
            currentEntry = sortedConsumers.lastEntry();
          }
          List<MemoryConsumer> cList = currentEntry.getValue();
          // 获取cList的最后一个元素
          MemoryConsumer c = cList.remove(cList.size() - 1);
          // 如果cList为空了，就把它从sortedConsumers中删除。
          if (cList.isEmpty()) {
            sortedConsumers.remove(currentEntry.getKey());
          }
          try {
            // TODO read consumer spill
            // 为什么consumer可以直接spill?不用询问consumer的意见吗???可能的解释是，
            // consumer只是用来使用内存存储记录的，并且被存储的记录在map端不会有很多的
            // 访问。当内存不够时，我们执行spill，把记录写到磁盘空间中，就能达到释放内存
            // 的作用的。另外，这些记录也不是没有了，而是在磁盘中了。
            // 这里有一个问题，我们是否可以对不同的consumer执行spill时，除了上面的内存
            // 占用多少这个优先级顺序，还能否有其它的参数来衡量优先级???
            // c(consumer)执行spill之后，释放了released大小的内存
            // 执行spill(不同的consumer有自己不同的实现)
            long released = c.spill(required - got, consumer);
            if (released > 0) {
              logger.debug("Task {} released {} from {} for {}", taskAttemptId,
                Utils.bytesToString(released), c, consumer);
              // 在释放完内存之后，重新向execution pool申请内存
              // 注意是: +=
              got += memoryManager.acquireExecutionMemory(required - got, taskAttemptId, mode);
              // 如果最终申请得到的内存got > 需要的内存required(说明此时内存足够了)，则跳出循环
              if (got >= required) {
                break;
              }
            }
          } catch (ClosedByInterruptException e) {
            // This called by user to kill a task (e.g: speculative task).
            logger.error("error while calling spill() on " + c, e);
            throw new RuntimeException(e.getMessage());
          } catch (IOException e) {
            logger.error("error while calling spill() on " + c, e);
            // 这里为什么是SparkOutOfMemoryError???
            throw new SparkOutOfMemoryError("error while calling spill() on " + c + " : "
              + e.getMessage());
          }
        }
      }

      // 如果在对所有的comsumers执行spill之后，内存还是不够(我天...),则对其自己执行spill
      // call spill() on itself
      if (got < required) {
        try {
          long released = consumer.spill(required - got, consumer);
          if (released > 0) {
            logger.debug("Task {} released {} from itself ({})", taskAttemptId,
              Utils.bytesToString(released), consumer);
            got += memoryManager.acquireExecutionMemory(required - got, taskAttemptId, mode);
          }
        } catch (ClosedByInterruptException e) {
          // This called by user to kill a task (e.g: speculative task).
          logger.error("error while calling spill() on " + consumer, e);
          throw new RuntimeException(e.getMessage());
        } catch (IOException e) {
          logger.error("error while calling spill() on " + consumer, e);
          throw new SparkOutOfMemoryError("error while calling spill() on " + consumer + " : "
            + e.getMessage());
        }
      } // 在执行完自身的spill后，got内存可能仍然小于required内存

      // 将该comsumer添加至consumers中
      consumers.add(consumer);
      logger.debug("Task {} acquired {} for {}", taskAttemptId, Utils.bytesToString(got), consumer);
      return got;
    }
  }

  /**
   * Release N bytes of execution memory for a MemoryConsumer.
   */
  public void releaseExecutionMemory(long size, MemoryConsumer consumer) {
    logger.debug("Task {} release {} from {}", taskAttemptId, Utils.bytesToString(size), consumer);
    memoryManager.releaseExecutionMemory(size, taskAttemptId, consumer.getMode());
  }

  /**
   * Dump the memory usage of all consumers.
   */
  public void showMemoryUsage() {
    logger.info("Memory used in task " + taskAttemptId);
    synchronized (this) {
      long memoryAccountedForByConsumers = 0;
      for (MemoryConsumer c: consumers) {
        long totalMemUsage = c.getUsed();
        memoryAccountedForByConsumers += totalMemUsage;
        if (totalMemUsage > 0) {
          logger.info("Acquired by " + c + ": " + Utils.bytesToString(totalMemUsage));
        }
      }
      long memoryNotAccountedFor =
        memoryManager.getExecutionMemoryUsageForTask(taskAttemptId) - memoryAccountedForByConsumers;
      logger.info(
        "{} bytes of memory were used by task {} but are not associated with specific consumers",
        memoryNotAccountedFor, taskAttemptId);
      logger.info(
        "{} bytes of memory are used for execution and {} bytes of memory are used for storage",
        memoryManager.executionMemoryUsed(), memoryManager.storageMemoryUsed());
    }
  }

  /**
   * Return the page size in bytes.
   */
  public long pageSizeBytes() {
    return memoryManager.pageSizeBytes();
  }

  /**
   * Allocate a block of memory that will be tracked in the MemoryManager's page table; this is
   * intended for allocating large blocks of Tungsten memory that will be shared between operators.
   *
   * Returns `null` if there was not enough memory to allocate the page. May return a page that
   * contains fewer bytes than requested, so callers should verify the size of returned pages.
   *
   * @throws TooLargePageException
   */
  public MemoryBlock allocatePage(long size, MemoryConsumer consumer) {
    assert(consumer != null);
    // 判断memory mode是否相同
    assert(consumer.getMode() == tungstenMemoryMode);
    // 如果需要申请的内存空间大于单页page的最大表示内存空间，
    // 则抛出TooLargePageException异常
    if (size > MAXIMUM_PAGE_SIZE_BYTES) {
      throw new TooLargePageException(size);
    }

    // 申请execution memory(acquired有可能小于size), 可能会触发spill
    long acquired = acquireExecutionMemory(size, consumer);
    if (acquired <= 0) {
      return null;
    }

    final int pageNumber;
    synchronized (this) {
      // nextClearBit：从下标formIndex开始，寻找第一个为设为false的bit位的下标
      pageNumber = allocatedPages.nextClearBit(0);
      // 说明所有的page都已经被分配使用了(page都没有了，内存还有???)
      if (pageNumber >= PAGE_TABLE_SIZE) {
        // 在抛出异常之前，释放刚刚申请的内存
        releaseExecutionMemory(acquired, consumer);
        throw new IllegalStateException(
          "Have already allocated a maximum of " + PAGE_TABLE_SIZE + " pages");
      }
      // 将该bit位置为true，表示该bit位对应的page被分配出去了
      allocatedPages.set(pageNumber);
    }
    MemoryBlock page = null;
    try {
      // 在从execution pool中申请到内存后，再通过allocator分配实际的内存
      page = memoryManager.tungstenMemoryAllocator().allocate(acquired);
    } catch (OutOfMemoryError e) {
      logger.warn("Failed to allocate a page ({} bytes), try again.", acquired);
      // 关于oom：
      // 上面的acquireExecutionMemory()是spark自己管理的内存池，而allocate()是向jvm申请内存，
      // 如果抛出oom，则说明spark以为还有acquired大小的内存，而实际上，jvm上的内存已经小于acquired了。
      // 说明，spark没有精确的记录内存的使用，有些内存的使用是没有跟踪到的。比如创建MemoryBlock时的
      // 内存申请。
      // there is no enough memory actually, it means the actual free memory is smaller than
      // MemoryManager thought, we should keep the acquired memory.
      synchronized (this) {
        // 为什么不把没有使用的内存释放掉???这样做不浪费内存吗???
        // see [SPARK-13210] [SQL] catch OOM when allocate memory and expand array 作者的解释
        // 从execution pool里申请到的acquired大小的内存(小于jvm的实际内存)，并不是说，jvm一点都
        // 分配不了(有可能它能分配acquired / 2大小的内存)，关键是，我们无法精确的知道，那些内存是spark的
        // 内存管理器，有多少是没有精确跟踪到的。
        acquiredButNotUsed += acquired;
        allocatedPages.clear(pageNumber);
      }
      // TODO 死循环??? 如果每次acquired都大于0(因为有其它的consumer在同时释放
      // (不包括spill)内存??? 这个'同时'可能吗???)
      // 问一个问题：一个executor会同时执行多个task吗???
      // 递归调用
      // this could trigger spilling to free some pages.
      return allocatePage(size, consumer);
    }
    page.pageNumber = pageNumber;
    pageTable[pageNumber] = page;
    if (logger.isTraceEnabled()) {
      logger.trace("Allocate page number {} ({} bytes)", pageNumber, acquired);
    }
    return page;
  }

  /**
   * Free a block of memory allocated via {@link TaskMemoryManager#allocatePage}.
   */
  public void freePage(MemoryBlock page, MemoryConsumer consumer) {
    assert (page.pageNumber != MemoryBlock.NO_PAGE_NUMBER) :
      "Called freePage() on memory that wasn't allocated with allocatePage()";
    assert (page.pageNumber != MemoryBlock.FREED_IN_ALLOCATOR_PAGE_NUMBER) :
      "Called freePage() on a memory block that has already been freed";
    assert (page.pageNumber != MemoryBlock.FREED_IN_TMM_PAGE_NUMBER) :
            "Called freePage() on a memory block that has already been freed";
    assert(allocatedPages.get(page.pageNumber));
    pageTable[page.pageNumber] = null;
    synchronized (this) {
      allocatedPages.clear(page.pageNumber);
    }
    if (logger.isTraceEnabled()) {
      logger.trace("Freed page number {} ({} bytes)", page.pageNumber, page.size());
    }
    long pageSize = page.size();
    // Clear the page number before passing the block to the MemoryAllocator's free().
    // Doing this allows the MemoryAllocator to detect when a TaskMemoryManager-managed
    // page has been inappropriately directly freed without calling TMM.freePage().
    page.pageNumber = MemoryBlock.FREED_IN_TMM_PAGE_NUMBER;
    memoryManager.tungstenMemoryAllocator().free(page);
    releaseExecutionMemory(pageSize, consumer);
  }

  /**
   * Given a memory page and offset within that page, encode this address into a 64-bit long.
   * This address will remain valid as long as the corresponding page has not been freed.
   *
   * @param page a data page allocated by {@link TaskMemoryManager#allocatePage}/
   * @param offsetInPage an offset in this page which incorporates the base offset. In other words,
   *                     this should be the value that you would pass as the base offset into an
   *                     UNSAFE call (e.g. page.baseOffset() + something).
   * @return an encoded page address.
   */
  public long encodePageNumberAndOffset(MemoryBlock page, long offsetInPage) {
    if (tungstenMemoryMode == MemoryMode.OFF_HEAP) {
      // 在off-heap模式下，如果该记录的大小超过了MemoryConsumer#pageSize,则我们会为该记录单独申请一个
      // page只用于存储这一个记录。在这种情况，这里的offsetInPage = 0；而如果该记录的大小小于
      // MemoryConsumer#pageSize，则这里的offsetInPage始终在PackedRecordPointer的用于编码offset的
      // 27个bit位的表示范围之内。

      // In off-heap mode, an offset is an absolute address that may require a full 64 bits to
      // encode. Due to our page size limitation, though, we can convert this into an offset that's
      // relative to the page's base offset; this relative offset will fit in 51 bits.
      offsetInPage -= page.getBaseOffset();
    } // 而对于on-heap的内存模式，该offsetInPage正是相对地址，不需要另作转换
    return encodePageNumberAndOffset(page.pageNumber, offsetInPage);
  }

  @VisibleForTesting
  public static long encodePageNumberAndOffset(int pageNumber, long offsetInPage) {
    assert (pageNumber >= 0) : "encodePageNumberAndOffset called with invalid page";
    return (((long) pageNumber) << OFFSET_BITS) | (offsetInPage & MASK_LONG_LOWER_51_BITS);
  }

  @VisibleForTesting
  public static int decodePageNumber(long pagePlusOffsetAddress) {
    return (int) (pagePlusOffsetAddress >>> OFFSET_BITS);
  }

  private static long decodeOffset(long pagePlusOffsetAddress) {
    return (pagePlusOffsetAddress & MASK_LONG_LOWER_51_BITS);
  }

  /**
   * 这个函数的功能应该是获取page的object。而只有ON HEAP的page有对应的object，
   * 而对于OFF HEAP的page是没有object的，所以返回null。
   * (感觉这函数的名字有点问题，还是另有所为???)
   * Get the page associated with an address encoded by
   * {@link TaskMemoryManager#encodePageNumberAndOffset(MemoryBlock, long)}
   */
  public Object getPage(long pagePlusOffsetAddress) {
    // 只能获取ON HEAP的page???哈???
    if (tungstenMemoryMode == MemoryMode.ON_HEAP) {
      final int pageNumber = decodePageNumber(pagePlusOffsetAddress);
      assert (pageNumber >= 0 && pageNumber < PAGE_TABLE_SIZE);
      final MemoryBlock page = pageTable[pageNumber];
      assert (page != null);
      assert (page.getBaseObject() != null);
      return page.getBaseObject();
    } else {
      // 因为在off-heap模式中，是以64bit的绝对地址来表示，所以，它没有base object
      return null;
    }
  }

  /**
   * Get the offset associated with an address encoded by
   * {@link TaskMemoryManager#encodePageNumberAndOffset(MemoryBlock, long)}
   */
  public long getOffsetInPage(long pagePlusOffsetAddress) {
    final long offsetInPage = decodeOffset(pagePlusOffsetAddress);
    if (tungstenMemoryMode == MemoryMode.ON_HEAP) {
      return offsetInPage;
    } else {
      // In off-heap mode, an offset is an absolute address. In encodePageNumberAndOffset, we
      // converted the absolute address into a relative address. Here, we invert that operation:
      final int pageNumber = decodePageNumber(pagePlusOffsetAddress);
      assert (pageNumber >= 0 && pageNumber < PAGE_TABLE_SIZE);
      final MemoryBlock page = pageTable[pageNumber];
      assert (page != null);
      return page.getBaseOffset() + offsetInPage;
    }
  }

  /**
   * Clean up all allocated memory and pages. Returns the number of bytes freed. A non-zero return
   * value can be used to detect memory leaks.
   */
  public long cleanUpAllAllocatedMemory() {
    synchronized (this) {
      for (MemoryConsumer c: consumers) {
        if (c != null && c.getUsed() > 0) {
          // In case of failed task, it's normal to see leaked memory
          logger.debug("unreleased " + Utils.bytesToString(c.getUsed()) + " memory from " + c);
        }
      }
      consumers.clear();

      for (MemoryBlock page : pageTable) {
        if (page != null) {
          logger.debug("unreleased page: " + page + " in task " + taskAttemptId);
          // 标记pageNumber为FREED_IN_TMM_PAGE_NUMBER，这样的话，接下来allocator才能释放为
          // page真正分配的内存
          page.pageNumber = MemoryBlock.FREED_IN_TMM_PAGE_NUMBER;
          memoryManager.tungstenMemoryAllocator().free(page);
        }
      }
      Arrays.fill(pageTable, null);
    }

    // 额，现在才release，不会浪费内存吗???
    // release the memory that is not used by any consumer (acquired for pages in tungsten mode).
    memoryManager.releaseExecutionMemory(acquiredButNotUsed, taskAttemptId, tungstenMemoryMode);

    // 在释放完真正分配的内存后，再释放由MemoryManager管理的execution memory
    return memoryManager.releaseAllExecutionMemoryForTask(taskAttemptId);
  }

  /**
   * Returns the memory consumption, in bytes, for the current task.
   */
  public long getMemoryConsumptionForThisTask() {
    return memoryManager.getExecutionMemoryUsageForTask(taskAttemptId);
  }

  /**
   * Returns Tungsten memory mode
   */
  public MemoryMode getTungstenMemoryMode() {
    return tungstenMemoryMode;
  }
}
