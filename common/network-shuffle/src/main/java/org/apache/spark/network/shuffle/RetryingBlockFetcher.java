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

package org.apache.spark.network.shuffle;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.common.collect.Sets;
import com.google.common.util.concurrent.Uninterruptibles;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.spark.network.buffer.ManagedBuffer;
import org.apache.spark.network.util.NettyUtils;
import org.apache.spark.network.util.TransportConf;

/**
 * Wraps another BlockFetcher with the ability to automatically retry fetches which fail due to
 * IOExceptions, which we hope are due to transient network conditions.
 *
 * This fetcher provides stronger guarantees regarding the parent BlockFetchingListener. In
 * particular, the listener will be invoked exactly once per blockId, with a success or failure.
 */
public class RetryingBlockFetcher {

  /**
   * Used to initiate the first fetch for all blocks, and subsequently for retrying the fetch on any
   * remaining blocks.
   */
  public interface BlockFetchStarter {
    /**
     * Creates a new BlockFetcher to fetch the given block ids which may do some synchronous
     * bootstrapping followed by fully asynchronous block fetching.
     * The BlockFetcher must eventually invoke the Listener on every input blockId, or else this
     * method must throw an exception.
     *
     * This method should always attempt to get a new TransportClient from the
     * {@link org.apache.spark.network.client.TransportClientFactory} in order to fix connection
     * issues.
     */
    void createAndStart(String[] blockIds, BlockFetchingListener listener)
         throws IOException, InterruptedException;
  }

  /** Shared executor service used for waiting and retrying. */
  private static final ExecutorService executorService = Executors.newCachedThreadPool(
    NettyUtils.createThreadFactory("Block Fetch Retry"));

  private static final Logger logger = LoggerFactory.getLogger(RetryingBlockFetcher.class);

  /** Used to initiate new Block Fetches on our remaining blocks. */
  private final BlockFetchStarter fetchStarter;

  /** Parent listener which we delegate all successful or permanently failed block fetches to. */
  private final BlockFetchingListener listener;

  /** Max number of times we are allowed to retry. */
  private final int maxRetries;

  /** Milliseconds to wait before each retry. */
  private final int retryWaitTime;

  // NOTE:
  // All of our non-final fields are synchronized under 'this' and should only be accessed/mutated
  // while inside a synchronized block.
  /** Number of times we've attempted to retry so far. */
  private int retryCount = 0;

  /**
   * Set of all block ids which have not been fetched successfully or with a non-IO Exception.
   * A retry involves requesting every outstanding block. Note that since this is a LinkedHashSet,
   * input ordering is preserved, so we always request blocks in the same order the user provided.
   */
  private final LinkedHashSet<String> outstandingBlocksIds;

  /**
   * The BlockFetchingListener that is active with our current BlockFetcher.
   * When we start a retry, we immediately replace this with a new Listener, which causes all any
   * old Listeners to ignore all further responses.
   */
  private RetryingBlockFetchListener currentListener;

  public RetryingBlockFetcher(
      TransportConf conf,
      BlockFetchStarter fetchStarter,
      String[] blockIds,
      BlockFetchingListener listener) {
    this.fetchStarter = fetchStarter;
    this.listener = listener;
    this.maxRetries = conf.maxIORetries();
    this.retryWaitTime = conf.ioRetryWaitTimeMs();
    // 又用了google common这个包
    this.outstandingBlocksIds = Sets.newLinkedHashSet();
    Collections.addAll(outstandingBlocksIds, blockIds);
    this.currentListener = new RetryingBlockFetchListener();
  }

  /**
   * Initiates the fetch of all blocks provided in the constructor, with possible retries in the
   * event of transient IOExceptions.
   */
  public void start() {
    fetchAllOutstanding();
  }

  /**
   * Fires off a request to fetch all blocks that have not been fetched successfully or permanently
   * failed (i.e., by a non-IOException).
   */
  private void fetchAllOutstanding() {
    // Start by retrieving our shared state within a synchronized block.
    String[] blockIdsToFetch;
    int numRetries;
    RetryingBlockFetchListener myListener;
    synchronized (this) {
      blockIdsToFetch = outstandingBlocksIds.toArray(new String[outstandingBlocksIds.size()]);
      numRetries = retryCount;
      myListener = currentListener;
    }

    // Now initiate the fetch on all outstanding blocks, possibly initiating a retry if that fails.
    try {
      // 这个fetchStarter就是NettyBlockTransferService#fetchBlocks里的blockFetchStarter啦
      // createAndStart()会通过创建OneForOneBlockFetcher，并调用其start()方法，开始发起远程请求。
      // 和其它地方不同的是，这里的myListener会拦截server的响应消息，如果是拉取失败的消息，则它会检查是否可以发起
      // 一个重试请求。如果可以，则重新发起一个请求。
      fetchStarter.createAndStart(blockIdsToFetch, myListener);
    } catch (Exception e) {
      logger.error(String.format("Exception while beginning fetch of %s outstanding blocks %s",
        blockIdsToFetch.length, numRetries > 0 ? "(after " + numRetries + " retries)" : ""), e);

      // 检查是否满足发起重试的条件
      if (shouldRetry(e)) {
        // TODO read
        initiateRetry();
      } else {
        // 如果不能发起重试，则该request中所有blocks就获取失败
        for (String bid : blockIdsToFetch) {
          listener.onBlockFetchFailure(bid, e);
        }
      }
    }
  }

  /**
   * TODO read retry之后，还能保证chunk有序获取吗？（这样想，获取失败，server端的chunkIndex如果没有
   * 更新，则retry没有问题。但是，如果chunkIndex更新了，chunk从server发送到client时出错了。这个时候
   * 再去retry，chunkIndex就对不上了啊。这个时候会有问题吧？？？）
   * Lightweight method which initiates a retry in a different thread. The retry will involve
   * calling fetchAllOutstanding() after a configured wait time.
   */
  private synchronized void initiateRetry() {
    retryCount += 1;
    currentListener = new RetryingBlockFetchListener();

    logger.info("Retrying fetch ({}/{}) for {} outstanding blocks after {} ms",
      retryCount, maxRetries, outstandingBlocksIds.size(), retryWaitTime);

    executorService.submit(() -> {
      Uninterruptibles.sleepUninterruptibly(retryWaitTime, TimeUnit.MILLISECONDS);
      fetchAllOutstanding();
    });
  }

  /**
   * Returns true if we should retry due a block fetch failure. We will retry if and only if
   * the exception was an IOException and we haven't retried 'maxRetries' times already.
   */
  private synchronized boolean shouldRetry(Throwable e) {
    boolean isIOException = e instanceof IOException
      || (e.getCause() != null && e.getCause() instanceof IOException);
    boolean hasRemainingRetries = retryCount < maxRetries;
    return isIOException && hasRemainingRetries;
  }

  /**
   * 我们的RetryListener会拦截block的拉取响应，然后再将响应传递给我们的parent listener。注意，在每次重试
   * 的事件中，我们会立即替换currentListener这个字段。这意味着任何不是来自当前的listener的响应都会被忽略。
   * Our RetryListener intercepts block fetch responses and forwards them to our parent listener.
   * Note that in the event of a retry, we will immediately replace the 'currentListener' field,
   * indicating that any responses from non-current Listeners should be ignored.
   */
  private class RetryingBlockFetchListener implements BlockFetchingListener {
    @Override
    public void onBlockFetchSuccess(String blockId, ManagedBuffer data) {
      // 我们只会在该block request是outstanding和我们仍是currentListener时，将该成功消息传递给parent listener。
      // We will only forward this success message to our parent listener if this block request is
      // outstanding and we are still the active listener.
      boolean shouldForwardSuccess = false;
      // 注意：我们需要对currentListener加锁
      synchronized (RetryingBlockFetcher.this) {
        if (this == currentListener && outstandingBlocksIds.contains(blockId)) {
          outstandingBlocksIds.remove(blockId);
          shouldForwardSuccess = true;
        }
      }

      // Now actually invoke the parent listener, outside of the synchronized block.
      if (shouldForwardSuccess) {
        listener.onBlockFetchSuccess(blockId, data);
      }
    }

    @Override
    public void onBlockFetchFailure(String blockId, Throwable exception) {
      // We will only forward this failure to our parent listener if this block request is
      // outstanding, we are still the active listener, AND we cannot retry the fetch.
      boolean shouldForwardFailure = false;
      synchronized (RetryingBlockFetcher.this) {
        if (this == currentListener && outstandingBlocksIds.contains(blockId)) {
          // 是否可以retry
          if (shouldRetry(exception)) {
            initiateRetry();
          } else {
            logger.error(String.format("Failed to fetch block %s, and will not retry (%s retries)",
              blockId, retryCount), exception);
            outstandingBlocksIds.remove(blockId);
            shouldForwardFailure = true;
          }
        }
      }

      // Now actually invoke the parent listener, outside of the synchronized block.
      if (shouldForwardFailure) {
        listener.onBlockFetchFailure(blockId, exception);
      }
    }
  }
}
