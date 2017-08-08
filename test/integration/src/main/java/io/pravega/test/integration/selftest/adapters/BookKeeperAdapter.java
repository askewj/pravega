/**
 * Copyright (c) 2017 Dell Inc., or its subsidiaries. All Rights Reserved.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 */

package io.pravega.test.integration.selftest.adapters;

import com.google.common.base.Preconditions;
import io.pravega.common.Exceptions;
import io.pravega.common.concurrent.ExecutorServiceHelpers;
import io.pravega.common.concurrent.FutureHelpers;
import io.pravega.common.lang.ProcessStarter;
import io.pravega.common.util.ArrayView;
import io.pravega.segmentstore.contracts.StreamSegmentExistsException;
import io.pravega.segmentstore.contracts.StreamSegmentNotExistsException;
import io.pravega.segmentstore.storage.DurableDataLog;
import io.pravega.segmentstore.storage.DurableDataLogException;
import io.pravega.segmentstore.storage.impl.bookkeeper.BookKeeperConfig;
import io.pravega.segmentstore.storage.impl.bookkeeper.BookKeeperLogFactory;
import io.pravega.segmentstore.storage.impl.bookkeeper.BookKeeperServiceRunner;
import io.pravega.segmentstore.storage.impl.bookkeeper.ZooKeeperServiceRunner;
import io.pravega.test.integration.selftest.Event;
import io.pravega.test.integration.selftest.TestConfig;
import io.pravega.test.integration.selftest.TestLogger;
import java.io.File;
import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.GuardedBy;
import lombok.val;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;

/**
 * Store adapter that executes requests directly to BookKeeper via the BookKeeperLog class.
 */
class BookKeeperAdapter implements StoreAdapter {
    //region Members

    private static final String LOG_ID = BookKeeperAdapter.class.getSimpleName();
    private final TestConfig testConfig;
    private final BookKeeperConfig bkConfig;
    private final ScheduledExecutorService executor;
    private final ConcurrentHashMap<String, DurableDataLog> logs;
    @GuardedBy("internalIds")
    private final HashMap<String, Integer> internalIds;
    private final AtomicBoolean closed;
    private final Thread stopBookKeeperProcess;
    private Process bookKeeperService;
    private CuratorFramework zkClient;
    private BookKeeperLogFactory logFactory;

    //endregion

    //region Constructor

    /**
     * Creates a new instance of the BookKeeperAdapter class.
     *
     * @param testConfig The Test Configuration to use.
     * @param bkConfig   The BookKeeper Configuration to use.
     * @param executor   An Executor to use for test-related async operations.
     */
    BookKeeperAdapter(TestConfig testConfig, BookKeeperConfig bkConfig, ScheduledExecutorService executor) {
        this.testConfig = Preconditions.checkNotNull(testConfig, "testConfig");
        this.bkConfig = Preconditions.checkNotNull(bkConfig, "bkConfig");
        this.executor = Preconditions.checkNotNull(executor, "executor");
        Preconditions.checkArgument(testConfig.getBookieCount() > 0, "BookKeeperAdapter requires at least one Bookie.");
        this.logs = new ConcurrentHashMap<>();
        this.internalIds = new HashMap<>();
        this.closed = new AtomicBoolean();
        this.stopBookKeeperProcess = new Thread(this::stopBookKeeper);
        Runtime.getRuntime().addShutdownHook(this.stopBookKeeperProcess);
    }

    //endregion

    //region AutoCloseable Implementation

    @Override
    public void close() {
        if (!this.closed.getAndSet(true)) {
            this.logs.values().forEach(DurableDataLog::close);
            this.logs.clear();

            BookKeeperLogFactory lf = this.logFactory;
            if (lf != null) {
                lf.close();
                this.logFactory = null;
            }

            stopBookKeeper();
            CuratorFramework zkClient = this.zkClient;
            if (zkClient != null) {
                zkClient.close();
                this.zkClient = null;
            }

            Runtime.getRuntime().removeShutdownHook(this.stopBookKeeperProcess);
            TestLogger.log(LOG_ID, "Closed.");
        }
    }

    private void stopBookKeeper() {
        val bk = this.bookKeeperService;
        if (bk != null) {
            bk.destroyForcibly();
            TestLogger.log(LOG_ID, "Bookies shut down.");
            this.bookKeeperService = null;
        }
    }

    //endregion

    //region StoreAdapter Implementation.

    @Override
    public boolean isFeatureSupported(Feature feature) {
        return feature == Feature.Create
                || feature == Feature.Append;
    }

    @Override
    public void initialize() throws Exception {
        Exceptions.checkNotClosed(this.closed.get(), this);
        Preconditions.checkState(this.bookKeeperService == null, "Cannot call initialize() after initialization happened.");

        // Start BookKeeper.
        this.bookKeeperService = BookKeeperAdapter.startBookKeeperOutOfProcess(this.testConfig, LOG_ID);

        // Create a ZK client.
        this.zkClient = CuratorFrameworkFactory
                .builder()
                .connectString("localhost:" + this.testConfig.getZkPort())
                .namespace("pravega")
                .retryPolicy(new ExponentialBackoffRetry(1000, 5))
                .sessionTimeoutMs(5000)
                .connectionTimeoutMs(5000)
                .build();
        this.zkClient.start();

        // Create a BK client.
        this.logFactory = new BookKeeperLogFactory(this.bkConfig, this.zkClient, this.executor);
        this.logFactory.initialize();
    }

    @Override
    public CompletableFuture<Void> createStream(String logName, Duration timeout) {
        Exceptions.checkNotClosed(this.closed.get(), this);

        int id;
        synchronized (this.internalIds) {
            if (this.internalIds.containsKey(logName)) {
                return FutureHelpers.failedFuture(new StreamSegmentExistsException(logName));
            }

            id = this.internalIds.size();
            this.internalIds.put(logName, id);
        }

        return CompletableFuture.runAsync(() -> {
            DurableDataLog log = null;
            boolean success = false;
            try {
                log = this.logFactory.createDurableDataLog(id);
                this.logs.put(logName, log);
                log.initialize(timeout);
                success = true;
            } catch (DurableDataLogException ex) {
                throw new CompletionException(ex);
            } finally {
                if (!success) {
                    this.logs.remove(logName);
                    synchronized (this.internalIds) {
                        this.internalIds.remove(logName);
                    }

                    if (log != null) {
                        log.close();
                    }
                }
            }
        }, this.executor);
    }

    @Override
    public CompletableFuture<Void> append(String logName, Event event, Duration timeout) {
        Exceptions.checkNotClosed(this.closed.get(), this);
        DurableDataLog log = this.logs.getOrDefault(logName, null);
        if (log == null) {
            return FutureHelpers.failedFuture(new StreamSegmentNotExistsException(logName));
        }

        ArrayView s = event.getSerialization();
        return FutureHelpers.toVoid(log.append(s, timeout));
    }

    @Override
    public StoreReader createReader() {
        throw new UnsupportedOperationException("createReader() is not supported on " + LOG_ID);
    }

    @Override
    public CompletableFuture<String> createTransaction(String parentStream, Duration timeout) {
        throw new UnsupportedOperationException("createTransaction() is not supported on " + LOG_ID);
    }

    @Override
    public CompletableFuture<Void> mergeTransaction(String transactionName, Duration timeout) {
        throw new UnsupportedOperationException("mergeTransaction() is not supported on " + LOG_ID);
    }

    @Override
    public CompletableFuture<Void> abortTransaction(String transactionName, Duration timeout) {
        throw new UnsupportedOperationException("abortTransaction() is not supported on " + LOG_ID);
    }

    @Override
    public CompletableFuture<Void> seal(String streamName, Duration timeout) {
        throw new UnsupportedOperationException("seal() is not supported on " + LOG_ID);
    }

    @Override
    public CompletableFuture<Void> delete(String streamName, Duration timeout) {
        throw new UnsupportedOperationException("delete() is not supported on " + LOG_ID);
    }

    @Override
    public ExecutorServiceHelpers.Snapshot getStorePoolSnapshot() {
        return null;
    }

    //endregion

    /**
     * Starts a BookKeeper (using a number of bookies) along with a ZooKeeper out-of-process.
     *
     * @param config The Test Config to use. This indicates the BK Port(s), ZK Port, as well as Bookie counts.
     * @param logId  A String to use for logging purposes.
     * @return A Process referring to the newly started Bookie process.
     * @throws IOException If an error occurred.
     */
    static Process startBookKeeperOutOfProcess(TestConfig config, String logId) throws IOException {
        int bookieCount = config.getBookieCount();
        Process p = ProcessStarter
                .forClass(BookKeeperServiceRunner.class)
                .sysProp(BookKeeperServiceRunner.PROPERTY_BASE_PORT, config.getBkPort(0))
                .sysProp(BookKeeperServiceRunner.PROPERTY_BOOKIE_COUNT, bookieCount)
                .sysProp(BookKeeperServiceRunner.PROPERTY_ZK_PORT, config.getZkPort())
                .sysProp(BookKeeperServiceRunner.PROPERTY_LEDGERS_PATH, TestConfig.BK_LEDGER_PATH)
                .sysProp(BookKeeperServiceRunner.PROPERTY_START_ZK, true)
                .stdOut(ProcessBuilder.Redirect.to(new File(config.getComponentOutLogPath("bk", 0))))
                .stdErr(ProcessBuilder.Redirect.to(new File(config.getComponentErrLogPath("bk", 0))))
                .start();
        ZooKeeperServiceRunner.waitForServerUp(config.getZkPort());
        TestLogger.log(logId, "Zookeeper (Port %s) and BookKeeper (Ports %s-%s) started.",
                config.getZkPort(), config.getBkPort(0), config.getBkPort(bookieCount - 1));
        return p;
    }
}
