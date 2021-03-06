/*
 * Copyright 2013-2016 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.glowroot.agent.fat.init;

import java.io.Closeable;
import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;

import javax.annotation.Nullable;
import javax.management.MBeanServer;

import com.google.common.base.Stopwatch;
import com.google.common.base.Strings;
import com.google.common.base.Ticker;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.fat.storage.PlatformMBeanServerLifecycle;
import org.glowroot.agent.fat.storage.SimpleRepoModule;
import org.glowroot.agent.fat.storage.util.DataSource;
import org.glowroot.agent.init.AgentModule;
import org.glowroot.agent.init.CollectorProxy;
import org.glowroot.agent.init.ProcessInfoCreator;
import org.glowroot.agent.util.LazyPlatformMBeanServer;
import org.glowroot.common.live.LiveTraceRepository.LiveTraceRepositoryNop;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.ui.CreateUiModuleBuilder;
import org.glowroot.ui.UiModule;
import org.glowroot.wire.api.Collector.AgentConfigUpdater;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.util.concurrent.TimeUnit.SECONDS;

class FatAgentModule {

    private final Ticker ticker;
    private final Clock clock;
    // only null in viewer mode
    private final @Nullable ScheduledExecutorService scheduledExecutor;
    private final SimpleRepoModule simpleRepoModule;
    private final @Nullable AgentModule agentModule;
    private final @Nullable ViewerAgentModule viewerAgentModule;
    private final File baseDir;

    private final Closeable dataDirLockingCloseable;

    private final String bindAddress;
    private final String version;

    private final boolean h2MemDb;

    private volatile @MonotonicNonNull UiModule uiModule;

    FatAgentModule(File baseDir, Map<String, String> properties,
            @Nullable Instrumentation instrumentation, @Nullable File glowrootJarFile,
            String glowrootVersion, boolean viewerMode) throws Exception {

        dataDirLockingCloseable = DataDirLocking.lockDataDir(baseDir);

        ticker = Ticker.systemTicker();
        clock = Clock.systemClock();

        // mem db is only used for testing (by glowroot-test-container)
        h2MemDb = Boolean.parseBoolean(properties.get("glowroot.internal.h2.memdb"));

        File dataDir = new File(baseDir, "data");
        DataSource dataSource;
        if (h2MemDb) {
            // mem db is only used for testing (by glowroot-test-container)
            dataSource = new DataSource();
        } else {
            dataSource = new DataSource(new File(dataDir, "data.h2.db"));
        }

        PluginCache pluginCache = PluginCache.create(glowrootJarFile, false);
        if (viewerMode) {
            viewerAgentModule = new ViewerAgentModule(baseDir, glowrootJarFile);
            scheduledExecutor = null;
            agentModule = null;
            ConfigRepository configRepository = ConfigRepositoryImpl.create(baseDir,
                    viewerAgentModule.getConfigService(), pluginCache);
            simpleRepoModule = new SimpleRepoModule(dataSource, dataDir, clock, ticker,
                    configRepository, null, true);
        } else {
            // trace module needs to be started as early as possible, so that weaving will be
            // applied to as many classes as possible
            // in particular, it needs to be started before StorageModule which uses shaded H2,
            // which loads java.sql.DriverManager, which loads 3rd party jdbc drivers found via
            // services/java.sql.Driver, and those drivers need to be woven
            CollectorProxy collectorProxy = new CollectorProxy();
            ConfigService configService =
                    ConfigService.create(baseDir, pluginCache.pluginDescriptors());
            agentModule = new AgentModule(clock, null, pluginCache, configService, collectorProxy,
                    instrumentation, baseDir);

            PreInitializeStorageShutdownClasses.preInitializeClasses();
            ConfigRepository configRepository = ConfigRepositoryImpl.create(baseDir,
                    agentModule.getConfigService(), pluginCache);
            ThreadFactory threadFactory = new ThreadFactoryBuilder().setDaemon(true)
                    .setNameFormat("Glowroot-Background-%d").build();
            scheduledExecutor = Executors.newScheduledThreadPool(2, threadFactory);
            simpleRepoModule = new SimpleRepoModule(dataSource, dataDir, clock, ticker,
                    configRepository, scheduledExecutor, false);
            simpleRepoModule.registerMBeans(
                    new PlatformMBeanServerLifecycleImpl(agentModule.getLazyPlatformMBeanServer()));

            // now inject the real collector into the proxy
            CollectorImpl collectorImpl =
                    new CollectorImpl(simpleRepoModule.getServerDao(),
                            simpleRepoModule.getAggregateRepository(),
                            simpleRepoModule.getTraceRepository(),
                            simpleRepoModule.getGaugeValueRepository(),
                            simpleRepoModule.getAlertingService());
            collectorProxy.setInstance(collectorImpl);
            // fat agent's CollectorImpl does nothing with agent config parameter
            collectorImpl.init(baseDir, ProcessInfoCreator.create(glowrootVersion),
                    AgentConfig.getDefaultInstance(), new AgentConfigUpdater() {
                        @Override
                        public void update(AgentConfig agentConfig) {}
                    });
            viewerAgentModule = null;
        }

        bindAddress = getBindAddress(properties);
        this.baseDir = baseDir;
        this.version = glowrootVersion;
    }

    void initEmbeddedServer() throws Exception {
        if (agentModule != null) {
            uiModule = new CreateUiModuleBuilder()
                    .central(false)
                    .ticker(ticker)
                    .clock(clock)
                    .logDir(baseDir)
                    .liveJvmService(agentModule.getLiveJvmService())
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .serverRepository(simpleRepoModule.getServerDao())
                    .transactionTypeRepository(simpleRepoModule.getTransactionTypeRepository())
                    .aggregateRepository(simpleRepoModule.getAggregateRepository())
                    .traceRepository(simpleRepoModule.getTraceRepository())
                    .gaugeValueRepository(simpleRepoModule.getGaugeValueRepository())
                    .repoAdmin(simpleRepoModule.getRepoAdmin())
                    .rollupLevelService(simpleRepoModule.getRollupLevelService())
                    .liveTraceRepository(agentModule.getLiveTraceRepository())
                    .liveWeavingService(agentModule.getLiveWeavingService())
                    .bindAddress(bindAddress)
                    .numWorkerThreads(2)
                    .version(version)
                    .build();
        } else {
            checkNotNull(viewerAgentModule);
            uiModule = new CreateUiModuleBuilder()
                    .central(false)
                    .ticker(ticker)
                    .clock(clock)
                    .logDir(baseDir)
                    .liveJvmService(null)
                    .configRepository(simpleRepoModule.getConfigRepository())
                    .serverRepository(simpleRepoModule.getServerDao())
                    .transactionTypeRepository(simpleRepoModule.getTransactionTypeRepository())
                    .aggregateRepository(simpleRepoModule.getAggregateRepository())
                    .traceRepository(simpleRepoModule.getTraceRepository())
                    .gaugeValueRepository(simpleRepoModule.getGaugeValueRepository())
                    .repoAdmin(simpleRepoModule.getRepoAdmin())
                    .rollupLevelService(simpleRepoModule.getRollupLevelService())
                    .liveTraceRepository(new LiveTraceRepositoryNop())
                    .liveWeavingService(null)
                    .bindAddress(bindAddress)
                    .numWorkerThreads(10)
                    .version(version)
                    .build();
        }
    }

    private static String getBindAddress(Map<String, String> properties) {
        // empty check to support parameterized script, e.g. -Dglowroot.ui.bind.address=${somevar}
        String bindAddress = properties.get("glowroot.ui.bind.address");
        if (Strings.isNullOrEmpty(bindAddress)) {
            return "0.0.0.0";
        } else {
            return bindAddress;
        }
    }

    @OnlyUsedByTests
    public SimpleRepoModule getSimpleRepoModule() {
        return simpleRepoModule;
    }

    @OnlyUsedByTests
    public AgentModule getAgentModule() {
        checkNotNull(agentModule);
        return agentModule;
    }

    @OnlyUsedByTests
    public UiModule getUiModule() throws InterruptedException {
        Stopwatch stopwatch = Stopwatch.createStarted();
        while (stopwatch.elapsed(SECONDS) < 60) {
            if (uiModule != null) {
                return uiModule;
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("UI Module failed to start");
    }

    @OnlyUsedByTests
    public void close() throws Exception {
        if (uiModule != null) {
            uiModule.close();
        }
        if (agentModule != null) {
            agentModule.close();
        }
        simpleRepoModule.close();
        if (scheduledExecutor != null) {
            // close scheduled executor last to prevent exceptions due to above modules attempting
            // to use a shutdown executor
            scheduledExecutor.shutdown();
            if (!scheduledExecutor.awaitTermination(10, SECONDS)) {
                throw new IllegalStateException("Could not terminate executor");
            }
        }
        // and unlock the data directory
        dataDirLockingCloseable.close();
    }

    private static class PlatformMBeanServerLifecycleImpl implements PlatformMBeanServerLifecycle {

        private final LazyPlatformMBeanServer lazyPlatformMBeanServer;

        private PlatformMBeanServerLifecycleImpl(LazyPlatformMBeanServer lazyPlatformMBeanServer) {
            this.lazyPlatformMBeanServer = lazyPlatformMBeanServer;
        }

        @Override
        public void addInitListener(final InitListener listener) {
            lazyPlatformMBeanServer.addInitListener(
                    new org.glowroot.agent.util.LazyPlatformMBeanServer.InitListener() {
                        @Override
                        public void postInit(MBeanServer mbeanServer) throws Exception {
                            listener.doWithPlatformMBeanServer(mbeanServer);
                        }
                    });
        }
    }
}
