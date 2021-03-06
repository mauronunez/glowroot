/*
 * Copyright 2011-2016 the original author or authors.
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

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import javax.annotation.Nullable;
import javax.crypto.SecretKey;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.io.Files;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.AdvancedConfig;
import org.glowroot.agent.config.ConfigService;
import org.glowroot.agent.config.ConfigService.ShadeProtectedTypeReference;
import org.glowroot.agent.config.GaugeConfig;
import org.glowroot.agent.config.InstrumentationConfig;
import org.glowroot.agent.config.PluginCache;
import org.glowroot.agent.config.PluginConfig;
import org.glowroot.agent.config.PluginDescriptor;
import org.glowroot.agent.config.TransactionConfig;
import org.glowroot.agent.config.UserRecordingConfig;
import org.glowroot.common.util.Versions;
import org.glowroot.storage.config.AlertConfig;
import org.glowroot.storage.config.ImmutableAlertConfig;
import org.glowroot.storage.config.ImmutableSmtpConfig;
import org.glowroot.storage.config.ImmutableStorageConfig;
import org.glowroot.storage.config.ImmutableUserInterfaceConfig;
import org.glowroot.storage.config.SmtpConfig;
import org.glowroot.storage.config.StorageConfig;
import org.glowroot.storage.config.UserInterfaceConfig;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.util.Encryption;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig.PluginProperty;

import static com.google.common.base.Preconditions.checkState;

class ConfigRepositoryImpl implements ConfigRepository {

    private static final Logger logger = LoggerFactory.getLogger(ConfigRepositoryImpl.class);

    private static final String ALERTS_KEY = "alerts";

    private final ConfigService configService;
    private final PluginCache pluginCache;
    private final File secretFile;

    private final Object writeLock = new Object();

    private final ImmutableList<RollupConfig> rollupConfigs;

    private volatile UserInterfaceConfig userInterfaceConfig;
    private volatile StorageConfig storageConfig;
    private volatile SmtpConfig smtpConfig;
    private volatile ImmutableList<AlertConfig> alertConfigs;

    // volatile not needed as access is guarded by secretFile
    private @MonotonicNonNull SecretKey secretKey;

    static ConfigRepository create(File baseDir, ConfigService configService,
            PluginCache pluginCache) {
        ConfigRepositoryImpl configRepository =
                new ConfigRepositoryImpl(baseDir, configService, pluginCache);
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        try {
            configRepository.writeAll();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return configRepository;
    }

    private ConfigRepositoryImpl(File baseDir, ConfigService configService,
            PluginCache pluginCache) {
        this.configService = configService;
        this.pluginCache = pluginCache;
        secretFile = new File(baseDir, "secret");
        rollupConfigs = ImmutableList.copyOf(RollupConfig.buildRollupConfigs());

        UserInterfaceConfig userInterfaceConfig =
                configService.getOtherConfig(UI_KEY, ImmutableUserInterfaceConfig.class);
        if (userInterfaceConfig == null) {
            this.userInterfaceConfig = ImmutableUserInterfaceConfig.builder().build();
        } else {
            this.userInterfaceConfig = userInterfaceConfig;
        }
        StorageConfig storageConfig =
                configService.getOtherConfig(STORAGE_KEY, ImmutableStorageConfig.class);
        if (storageConfig == null) {
            this.storageConfig = ImmutableStorageConfig.builder().build();
        } else if (storageConfig.hasListIssues()) {
            this.storageConfig = withCorrectedLists(storageConfig);
        } else {
            this.storageConfig = storageConfig;
        }
        SmtpConfig smtpConfig = configService.getOtherConfig(SMTP_KEY, ImmutableSmtpConfig.class);
        if (smtpConfig == null) {
            this.smtpConfig = ImmutableSmtpConfig.builder().build();
        } else {
            this.smtpConfig = smtpConfig;
        }
        List<ImmutableAlertConfig> alertConfigs = configService.getOtherConfig(ALERTS_KEY,
                new ShadeProtectedTypeReference<List<ImmutableAlertConfig>>() {});
        if (alertConfigs == null) {
            this.alertConfigs = ImmutableList.of();
        } else {
            this.alertConfigs = ImmutableList.<AlertConfig>copyOf(alertConfigs);
        }
    }

    @Override
    public AgentConfig.TransactionConfig getTransactionConfig(String serverId) {
        return configService.getTransactionConfig().toProto();
    }

    @Override
    public AgentConfig.UserRecordingConfig getUserRecordingConfig(String serverId) {
        return configService.getUserRecordingConfig().toProto();
    }

    @Override
    public AgentConfig.AdvancedConfig getAdvancedConfig(String serverId) {
        return configService.getAdvancedConfig().toProto();
    }

    @Override
    public List<AgentConfig.PluginConfig> getPluginConfigs(String serverId) {
        List<AgentConfig.PluginConfig> configs = Lists.newArrayList();
        for (PluginConfig config : configService.getPluginConfigs()) {
            configs.add(config.toProto());
        }
        return configs;
    }

    @Override
    public @Nullable AgentConfig.PluginConfig getPluginConfig(String serverId, String pluginId) {
        PluginConfig pluginConfig = configService.getPluginConfig(pluginId);
        if (pluginConfig == null) {
            return null;
        }
        return pluginConfig.toProto();
    }

    @Override
    public List<AgentConfig.GaugeConfig> getGaugeConfigs(String serverId) {
        List<AgentConfig.GaugeConfig> gaugeConfigs = Lists.newArrayList();
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            gaugeConfigs.add(gaugeConfig.toProto());
        }
        return gaugeConfigs;
    }

    @Override
    public @Nullable AgentConfig.GaugeConfig getGaugeConfig(String serverId, String version) {
        for (GaugeConfig gaugeConfig : configService.getGaugeConfigs()) {
            AgentConfig.GaugeConfig config = gaugeConfig.toProto();
            if (Versions.getVersion(config).equals(version)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public List<AgentConfig.InstrumentationConfig> getInstrumentationConfigs(String serverId) {
        List<AgentConfig.InstrumentationConfig> configs = Lists.newArrayList();
        for (InstrumentationConfig config : configService.getInstrumentationConfigs()) {
            configs.add(config.toProto());
        }
        return configs;
    }

    @Override
    public @Nullable AgentConfig.InstrumentationConfig getInstrumentationConfig(String serverId,
            String version) {
        for (InstrumentationConfig instrumentationConfig : configService
                .getInstrumentationConfigs()) {
            AgentConfig.InstrumentationConfig config = instrumentationConfig.toProto();
            if (Versions.getVersion(config).equals(version)) {
                return config;
            }
        }
        return null;
    }

    @Override
    public UserInterfaceConfig getUserInterfaceConfig() {
        return userInterfaceConfig;
    }

    @Override
    public StorageConfig getStorageConfig() {
        return storageConfig;
    }

    @Override
    public SmtpConfig getSmtpConfig() {
        return smtpConfig;
    }

    @Override
    public List<AlertConfig> getAlertConfigs(String serverId) {
        return alertConfigs;
    }

    @Override
    public @Nullable AlertConfig getAlertConfig(String serverId, String version) {
        for (AlertConfig alertConfig : alertConfigs) {
            if (alertConfig.version().equals(version)) {
                return alertConfig;
            }
        }
        return null;
    }

    @Override
    public void updateTransactionConfig(String serverId,
            AgentConfig.TransactionConfig updatedConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            String currVersion =
                    Versions.getVersion(configService.getTransactionConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateTransactionConfig(TransactionConfig.create(updatedConfig));
        }
    }

    @Override
    public void updateUserRecordingConfig(String serverId,
            AgentConfig.UserRecordingConfig userRecordingConfig, String priorVersion)
                    throws Exception {
        synchronized (writeLock) {
            String currVersion =
                    Versions.getVersion(configService.getUserRecordingConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService
                    .updateUserRecordingConfig(UserRecordingConfig.create(userRecordingConfig));
        }
    }

    @Override
    public void updateAdvancedConfig(String serverId, AgentConfig.AdvancedConfig advancedConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            String currVersion = Versions.getVersion(configService.getAdvancedConfig().toProto());
            checkVersionsEqual(currVersion, priorVersion);
            configService.updateAdvancedConfig(AdvancedConfig.create(advancedConfig));
        }
    }

    @Override
    public void updatePluginConfig(String serverId, String pluginId,
            List<PluginProperty> properties, String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<PluginConfig> configs = Lists.newArrayList(configService.getPluginConfigs());
            boolean found = false;
            for (ListIterator<PluginConfig> i = configs.listIterator(); i.hasNext();) {
                PluginConfig loopPluginConfig = i.next();
                if (pluginId.equals(loopPluginConfig.id())) {
                    String currVersion = Versions.getVersion(loopPluginConfig.toProto());
                    checkVersionsEqual(currVersion, priorVersion);
                    PluginDescriptor pluginDescriptor = getPluginDescriptor(pluginId);
                    i.set(PluginConfig.create(pluginDescriptor, properties));
                    found = true;
                    break;
                }
            }
            checkState(found, "Plugin config not found: %s", pluginId);
            configService.updatePluginConfigs(configs);
        }
    }

    private PluginDescriptor getPluginDescriptor(String pluginId) {
        for (PluginDescriptor pluginDescriptor : pluginCache.pluginDescriptors()) {
            if (pluginDescriptor.id().equals(pluginId)) {
                return pluginDescriptor;
            }
        }
        throw new IllegalStateException("Could not find plugin descriptor: " + pluginId);
    }

    @Override
    public void insertInstrumentationConfig(String serverId,
            AgentConfig.InstrumentationConfig instrumentationConfig) throws IOException {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            configs.add(InstrumentationConfig.create(instrumentationConfig));
            configService.updateInstrumentationConfigs(configs);
        }
    }

    @Override
    public void updateInstrumentationConfig(String serverId,
            AgentConfig.InstrumentationConfig instrumentationConfig, String priorVersion)
                    throws Exception {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                String currVersion = Versions.getVersion(i.next().toProto());
                if (priorVersion.equals(currVersion)) {
                    i.set(InstrumentationConfig.create(instrumentationConfig));
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateInstrumentationConfigs(configs);
        }
    }

    @Override
    public void deleteInstrumentationConfig(String serverId, String version) throws Exception {
        synchronized (writeLock) {
            List<InstrumentationConfig> configs =
                    Lists.newArrayList(configService.getInstrumentationConfigs());
            boolean found = false;
            for (ListIterator<InstrumentationConfig> i = configs.listIterator(); i.hasNext();) {
                String currVersion = Versions.getVersion(i.next().toProto());
                if (version.equals(currVersion)) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateInstrumentationConfigs(configs);
        }
    }

    @Override
    public void insertGaugeConfig(String serverId, AgentConfig.GaugeConfig gaugeConfig)
            throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            // check for duplicate mbeanObjectName
            for (GaugeConfig loopConfig : configs) {
                if (loopConfig.mbeanObjectName().equals(gaugeConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            configs.add(GaugeConfig.create(gaugeConfig));
            configService.updateGaugeConfigs(configs);
        }
    }

    @Override
    public void updateGaugeConfig(String serverId, AgentConfig.GaugeConfig gaugeConfig,
            String priorVersion) throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                GaugeConfig loopConfig = i.next();
                String currVersion = Versions.getVersion(loopConfig.toProto());
                if (priorVersion.equals(currVersion)) {
                    i.set(GaugeConfig.create(gaugeConfig));
                    found = true;
                    break;
                } else if (loopConfig.mbeanObjectName().equals(gaugeConfig.getMbeanObjectName())) {
                    throw new DuplicateMBeanObjectNameException();
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateGaugeConfigs(configs);
        }
    }

    @Override
    public void deleteGaugeConfig(String serverId, String version) throws Exception {
        synchronized (writeLock) {
            List<GaugeConfig> configs = Lists.newArrayList(configService.getGaugeConfigs());
            boolean found = false;
            for (ListIterator<GaugeConfig> i = configs.listIterator(); i.hasNext();) {
                String currVersion = Versions.getVersion(i.next().toProto());
                if (version.equals(currVersion)) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new OptimisticLockException();
            }
            configService.updateGaugeConfigs(configs);
        }
    }

    @Override
    public void updateUserInterfaceConfig(UserInterfaceConfig updatedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(userInterfaceConfig.version(), priorVersion);
            configService.updateOtherConfig(UI_KEY, updatedConfig);
            userInterfaceConfig = updatedConfig;
        }
    }

    @Override
    public void updateStorageConfig(StorageConfig updatedConfig, String priorVersion)
            throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(storageConfig.version(), priorVersion);
            configService.updateOtherConfig(STORAGE_KEY, updatedConfig);
            storageConfig = updatedConfig;
        }
    }

    @Override
    public void updateSmtpConfig(SmtpConfig updatedConfig, String priorVersion) throws Exception {
        synchronized (writeLock) {
            checkVersionsEqual(smtpConfig.version(), priorVersion);
            configService.updateOtherConfig(SMTP_KEY, updatedConfig);
            smtpConfig = updatedConfig;
        }
    }

    @Override
    public void insertAlertConfig(String serverId, AlertConfig alertConfig) throws Exception {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(alertConfigs);
            configs.add(alertConfig);
            configService.updateOtherConfig(ALERTS_KEY, configs);
            this.alertConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void updateAlertConfig(String serverId, AlertConfig alertConfig, String priorVersion)
            throws IOException {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(alertConfigs);
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                if (priorVersion.equals(i.next().version())) {
                    i.set(alertConfig);
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", priorVersion);
            configService.updateOtherConfig(ALERTS_KEY, configs);
            alertConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public void deleteAlertConfig(String serverId, String version) throws IOException {
        synchronized (writeLock) {
            List<AlertConfig> configs = Lists.newArrayList(alertConfigs);
            boolean found = false;
            for (ListIterator<AlertConfig> i = configs.listIterator(); i.hasNext();) {
                if (version.equals(i.next().version())) {
                    i.remove();
                    found = true;
                    break;
                }
            }
            checkState(found, "Alert config not found: %s", version);
            configService.updateOtherConfig(ALERTS_KEY, configs);
            alertConfigs = ImmutableList.copyOf(configs);
        }
    }

    @Override
    public long getGaugeCollectionIntervalMillis() {
        return configService.getGaugeCollectionIntervalMillis();
    }

    @Override
    public ImmutableList<RollupConfig> getRollupConfigs() {
        return rollupConfigs;
    }

    // lazy create secret file only when needed
    @Override
    public SecretKey getSecretKey() throws Exception {
        synchronized (secretFile) {
            if (secretKey == null) {
                if (secretFile.exists()) {
                    secretKey = Encryption.loadKey(secretFile);
                } else {
                    secretKey = Encryption.generateNewKey();
                    Files.write(secretKey.getEncoded(), secretFile);
                }
            }
            return secretKey;
        }
    }

    private void checkVersionsEqual(String version, String priorVersion)
            throws OptimisticLockException {
        if (!version.equals(priorVersion)) {
            throw new OptimisticLockException();
        }
    }

    private void writeAll() throws IOException {
        // linked hash map to preserve ordering when writing to config file
        Map<String, Object> configs = Maps.newLinkedHashMap();
        configs.put(UI_KEY, userInterfaceConfig);
        configs.put(STORAGE_KEY, storageConfig);
        configs.put(SMTP_KEY, smtpConfig);
        configs.put(ALERTS_KEY, alertConfigs);
        configService.updateOtherConfigs(configs);
    }

    private static StorageConfig withCorrectedLists(StorageConfig storageConfig) {
        StorageConfig defaultConfig = ImmutableStorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(storageConfig.rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        ImmutableList<Integer> rollupCappedDatabaseSizesMb =
                fix(storageConfig.rollupCappedDatabaseSizesMb(),
                        defaultConfig.rollupCappedDatabaseSizesMb());
        return ImmutableStorageConfig.builder()
                .copyFrom(storageConfig)
                .rollupExpirationHours(rollupExpirationHours)
                .rollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb)
                .build();
    }

    private static ImmutableList<Integer> fix(ImmutableList<Integer> thisList,
            List<Integer> defaultList) {
        if (thisList.size() >= defaultList.size()) {
            return thisList.subList(0, defaultList.size());
        }
        List<Integer> correctedList = Lists.newArrayList(thisList);
        for (int i = thisList.size(); i < defaultList.size(); i++) {
            correctedList.add(defaultList.get(i));
        }
        return ImmutableList.copyOf(correctedList);
    }
}
