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
package org.glowroot.agent.config;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Ordering;
import com.google.common.collect.Sets;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.agent.config.PropertyValue.PropertyType;
import org.glowroot.agent.plugin.api.config.ConfigListener;
import org.glowroot.agent.util.JavaVersion;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.common.util.OnlyUsedByTests;
import org.glowroot.wire.api.model.AgentConfigOuterClass.AgentConfig;

public class ConfigService {

    private static final Logger logger = LoggerFactory.getLogger(ConfigService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private static final long GAUGE_COLLECTION_INTERVAL_MILLIS =
            Long.getLong("glowroot.internal.gaugeCollectionIntervalMillis", 5000);

    private final ConfigFile configFile;

    private final ImmutableList<PluginDescriptor> pluginDescriptors;

    private final Set<ConfigListener> configListeners = Sets.newCopyOnWriteArraySet();
    private final Set<ConfigListener> pluginConfigListeners = Sets.newCopyOnWriteArraySet();

    private volatile TransactionConfig transactionConfig;
    private volatile UserRecordingConfig userRecordingConfig;
    private volatile AdvancedConfig advancedConfig;
    private volatile ImmutableList<PluginConfig> pluginConfigs;
    private volatile ImmutableList<GaugeConfig> gaugeConfigs;
    private volatile ImmutableList<InstrumentationConfig> instrumentationConfigs;

    // memory barrier is used to ensure memory visibility of config values
    private volatile boolean memoryBarrier;

    public static ConfigService create(File baseDir, List<PluginDescriptor> pluginDescriptors) {
        ConfigService configService = new ConfigService(baseDir, pluginDescriptors);
        // it's nice to update config.json on startup if it is missing some/all config
        // properties so that the file contents can be reviewed/updated/copied if desired
        try {
            configService.writeAll();
        } catch (IOException e) {
            logger.error(e.getMessage(), e);
        }
        return configService;
    }

    private ConfigService(File baseDir, List<PluginDescriptor> pluginDescriptors) {
        configFile = new ConfigFile(new File(baseDir, "config.json"));
        this.pluginDescriptors = ImmutableList.copyOf(pluginDescriptors);
        TransactionConfig transactionConfig =
                configFile.getNode("transactions", ImmutableTransactionConfig.class, mapper);
        if (transactionConfig == null) {
            this.transactionConfig = ImmutableTransactionConfig.builder().build();
        } else {
            this.transactionConfig = transactionConfig;
        }
        UserRecordingConfig userRecordingConfig =
                configFile.getNode("userRecording", ImmutableUserRecordingConfig.class, mapper);
        if (userRecordingConfig == null) {
            this.userRecordingConfig = ImmutableUserRecordingConfig.builder().build();
        } else {
            this.userRecordingConfig = userRecordingConfig;
        }
        AdvancedConfig advancedConfig =
                configFile.getNode("advanced", ImmutableAdvancedConfig.class, mapper);
        if (advancedConfig == null) {
            this.advancedConfig = ImmutableAdvancedConfig.builder().build();
        } else {
            this.advancedConfig = advancedConfig;
        }
        List<ImmutablePluginConfigTemp> pluginConfigs = configFile.getNode("plugins",
                new TypeReference<List<ImmutablePluginConfigTemp>>() {}, mapper);
        this.pluginConfigs = fixPluginConfigs(pluginConfigs, pluginDescriptors);

        List<ImmutableGaugeConfig> gaugeConfigs = configFile.getNode("gauges",
                new TypeReference<List<ImmutableGaugeConfig>>() {}, mapper);
        if (gaugeConfigs == null) {
            this.gaugeConfigs = getDefaultGaugeConfigs();
        } else {
            this.gaugeConfigs = ImmutableList.<GaugeConfig>copyOf(gaugeConfigs);
        }
        List<ImmutableInstrumentationConfig> instrumentationConfigs =
                configFile.getNode("instrumentation",
                        new TypeReference<List<ImmutableInstrumentationConfig>>() {}, mapper);
        if (instrumentationConfigs == null) {
            this.instrumentationConfigs = ImmutableList.of();
        } else {
            this.instrumentationConfigs =
                    ImmutableList.<InstrumentationConfig>copyOf(instrumentationConfigs);
        }

        for (InstrumentationConfig instrumentationConfig : this.instrumentationConfigs) {
            ImmutableList<String> errors = instrumentationConfig.validationErrors();
            if (!errors.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("Invalid instrumentation config: ");
                sb.append(Joiner.on(", ").join(errors));
                sb.append(" ");
                try {
                    sb.append(ObjectMappers.create().writeValueAsString(instrumentationConfig));
                } catch (JsonProcessingException e) {
                    logger.error(e.getMessage(), e);
                }
                logger.error(sb.toString());
            }
        }
    }

    public TransactionConfig getTransactionConfig() {
        return transactionConfig;
    }

    public UserRecordingConfig getUserRecordingConfig() {
        return userRecordingConfig;
    }

    public AdvancedConfig getAdvancedConfig() {
        return advancedConfig;
    }

    public ImmutableList<PluginConfig> getPluginConfigs() {
        return pluginConfigs;
    }

    public @Nullable PluginConfig getPluginConfig(String pluginId) {
        for (PluginConfig pluginConfig : pluginConfigs) {
            if (pluginId.equals(pluginConfig.id())) {
                return pluginConfig;
            }
        }
        return null;
    }

    public List<GaugeConfig> getGaugeConfigs() {
        return gaugeConfigs;
    }

    public List<InstrumentationConfig> getInstrumentationConfigs() {
        return instrumentationConfigs;
    }

    public long getGaugeCollectionIntervalMillis() {
        return GAUGE_COLLECTION_INTERVAL_MILLIS;
    }

    public AgentConfig getAgentConfig() {
        AgentConfig.Builder builder = AgentConfig.newBuilder()
                .setTransactionConfig(transactionConfig.toProto())
                .setUserRecordingConfig(userRecordingConfig.toProto())
                .setAdvancedConfig(advancedConfig.toProto());
        for (PluginConfig pluginConfig : pluginConfigs) {
            builder.addPluginConfig(pluginConfig.toProto());
        }
        for (InstrumentationConfig instrumentationConfig : instrumentationConfigs) {
            builder.addInstrumentationConfig(instrumentationConfig.toProto());
        }
        for (GaugeConfig gaugeConfig : gaugeConfigs) {
            builder.addGaugeConfig(gaugeConfig.toProto());
        }
        return builder.build();
    }

    public void addConfigListener(ConfigListener listener) {
        configListeners.add(listener);
        listener.onChange();
    }

    public void addPluginConfigListener(ConfigListener listener) {
        pluginConfigListeners.add(listener);
    }

    public void updateTransactionConfig(TransactionConfig updatedConfig) throws IOException {
        configFile.write("transactions", updatedConfig, mapper);
        transactionConfig = updatedConfig;
        notifyConfigListeners();
    }

    public void updateUserRecordingConfig(UserRecordingConfig updatedConfig) throws IOException {
        configFile.write("userRecording", updatedConfig, mapper);
        userRecordingConfig = updatedConfig;
        notifyConfigListeners();
    }

    public void updateAdvancedConfig(AdvancedConfig updatedConfig) throws IOException {
        configFile.write("advanced", updatedConfig, mapper);
        advancedConfig = updatedConfig;
        notifyConfigListeners();
    }

    public void updatePluginConfigs(List<PluginConfig> updatedConfigs) throws IOException {
        configFile.write("plugins", updatedConfigs, mapper);
        pluginConfigs = ImmutableList.copyOf(updatedConfigs);
        notifyAllPluginConfigListeners();
    }

    public void updateInstrumentationConfigs(List<InstrumentationConfig> updatedConfigs)
            throws IOException {
        configFile.write("instrumentation", updatedConfigs, mapper);
        instrumentationConfigs = ImmutableList.copyOf(updatedConfigs);
        notifyConfigListeners();
    }

    public void updateGaugeConfigs(List<GaugeConfig> updatedConfigs) throws IOException {
        configFile.write("gauges", updatedConfigs, mapper);
        gaugeConfigs = ImmutableList.copyOf(updatedConfigs);
        notifyConfigListeners();
    }

    public <T extends /*@NonNull*/ Object> /*@Nullable*/ T getOtherConfig(String key,
            Class<T> clazz) {
        return configFile.getNode(key, clazz, mapper);
    }

    public <T extends /*@NonNull*/ Object> /*@Nullable*/ T getOtherConfig(String key,
            TypeReference<T> typeReference) {
        return configFile.getNode(key, typeReference, mapper);
    }

    public void updateOtherConfig(String key, Object config) throws IOException {
        configFile.write(key, config, mapper);
    }

    public void updateOtherConfigs(Map<String, Object> configs) throws IOException {
        configFile.write(configs, mapper);
    }

    public boolean readMemoryBarrier() {
        return memoryBarrier;
    }

    public void writeMemoryBarrier() {
        memoryBarrier = true;
    }

    // the updated config is not passed to the listeners to avoid the race condition of multiple
    // config updates being sent out of order, instead listeners must call get*Config() which will
    // never return the updates out of order (at worst it may return the most recent update twice
    // which is ok)
    private void notifyConfigListeners() {
        for (ConfigListener configListener : configListeners) {
            configListener.onChange();
        }
    }

    private void notifyAllPluginConfigListeners() {
        for (ConfigListener listener : pluginConfigListeners) {
            listener.onChange();
        }
    }

    @OnlyUsedByTests
    public void resetAllConfig() throws IOException {
        transactionConfig = ImmutableTransactionConfig.builder()
                .slowThresholdMillis(0)
                .build();
        userRecordingConfig = ImmutableUserRecordingConfig.builder().build();
        advancedConfig = ImmutableAdvancedConfig.builder().build();
        pluginConfigs =
                fixPluginConfigs(ImmutableList.<ImmutablePluginConfigTemp>of(), pluginDescriptors);
        gaugeConfigs = getDefaultGaugeConfigs();
        instrumentationConfigs = ImmutableList.of();
        writeAll();
        notifyConfigListeners();
        notifyAllPluginConfigListeners();
    }

    private void writeAll() throws IOException {
        // linked hash map to preserve ordering when writing to config file
        Map<String, Object> configs = Maps.newLinkedHashMap();
        configs.put("transactions", transactionConfig);
        configs.put("userRecording", userRecordingConfig);
        configs.put("advanced", advancedConfig);
        configs.put("plugins", this.pluginConfigs);
        configs.put("gauges", this.gaugeConfigs);
        configs.put("instrumentation", this.instrumentationConfigs);
        configFile.write(configs, mapper);
    }

    private static ImmutableList<GaugeConfig> getDefaultGaugeConfigs() {
        List<GaugeConfig> defaultGaugeConfigs = Lists.newArrayList();
        defaultGaugeConfigs.add(ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=Memory")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("HeapMemoryUsage/used", false))
                .build());
        defaultGaugeConfigs.add(ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=GarbageCollector,name=*")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("CollectionCount", true))
                .addMbeanAttributes(ImmutableMBeanAttribute.of("CollectionTime", true))
                .build());
        defaultGaugeConfigs.add(ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=MemoryPool,name=*")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("Usage/used", false))
                .build());
        ImmutableGaugeConfig.Builder operatingSystemMBean = ImmutableGaugeConfig.builder()
                .mbeanObjectName("java.lang:type=OperatingSystem")
                .addMbeanAttributes(ImmutableMBeanAttribute.of("FreePhysicalMemorySize", false));
        if (!JavaVersion.isJava6()) {
            // these are only available since 1.7
            operatingSystemMBean
                    .addMbeanAttributes(ImmutableMBeanAttribute.of("ProcessCpuLoad", false));
            operatingSystemMBean
                    .addMbeanAttributes(ImmutableMBeanAttribute.of("SystemCpuLoad", false));
        }
        defaultGaugeConfigs.add(operatingSystemMBean.build());
        return ImmutableList.copyOf(defaultGaugeConfigs);
    }

    private static ImmutableList<PluginConfig> fixPluginConfigs(
            @Nullable List<ImmutablePluginConfigTemp> filePluginConfigs,
            List<PluginDescriptor> pluginDescriptors) {

        // sorted by id for writing to config file
        List<PluginDescriptor> sortedPluginDescriptors =
                new PluginDescriptorOrdering().immutableSortedCopy(pluginDescriptors);

        Map<String, PluginConfigTemp> filePluginConfigMap = Maps.newHashMap();
        if (filePluginConfigs != null) {
            for (ImmutablePluginConfigTemp pluginConfig : filePluginConfigs) {
                filePluginConfigMap.put(pluginConfig.id(), pluginConfig);
            }
        }

        List<PluginConfig> accuratePluginConfigs = Lists.newArrayList();
        for (PluginDescriptor pluginDescriptor : sortedPluginDescriptors) {
            PluginConfigTemp filePluginConfig = filePluginConfigMap.get(pluginDescriptor.id());
            ImmutablePluginConfig.Builder builder = ImmutablePluginConfig.builder()
                    .pluginDescriptor(pluginDescriptor);
            for (PropertyDescriptor propertyDescriptor : pluginDescriptor.properties()) {
                builder.putProperties(propertyDescriptor.name(),
                        getPropertyValue(filePluginConfig, propertyDescriptor));
            }
            accuratePluginConfigs.add(builder.build());
        }
        return ImmutableList.copyOf(accuratePluginConfigs);
    }

    private static PropertyValue getPropertyValue(@Nullable PluginConfigTemp pluginConfig,
            PropertyDescriptor propertyDescriptor) {
        if (pluginConfig == null) {
            return propertyDescriptor.getValidatedNonNullDefaultValue();
        }
        PropertyValue propertyValue = getValidatedPropertyValue(pluginConfig.properties(),
                propertyDescriptor.name(), propertyDescriptor.type());
        if (propertyValue == null) {
            return propertyDescriptor.getValidatedNonNullDefaultValue();
        }
        return propertyValue;
    }

    private static @Nullable PropertyValue getValidatedPropertyValue(
            Map<String, PropertyValue> properties, String propertyName, PropertyType propertyType) {
        PropertyValue propertyValue = properties.get(propertyName);
        if (propertyValue == null) {
            return null;
        }
        Object value = propertyValue.value();
        if (value == null) {
            return PropertyValue.getDefaultValue(propertyType);
        }
        if (PropertyDescriptor.isValidType(value, propertyType)) {
            return propertyValue;
        } else {
            logger.warn("invalid value for plugin property: {}", propertyName);
            return PropertyValue.getDefaultValue(propertyType);
        }
    }

    public static class ShadeProtectedTypeReference<T extends /*@NonNull*/ Object>
            extends TypeReference<T> {}

    private static class PluginDescriptorOrdering extends Ordering<PluginDescriptor> {
        @Override
        public int compare(PluginDescriptor left, PluginDescriptor right) {
            return left.id().compareToIgnoreCase(right.id());
        }
    }

    @Value.Immutable
    interface PluginConfigTemp {
        String id();
        Map<String, PropertyValue> properties();
    }
}
