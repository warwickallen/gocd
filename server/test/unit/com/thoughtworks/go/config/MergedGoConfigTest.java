/*
 * Copyright 2016 ThoughtWorks, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.thoughtworks.go.config;

import com.thoughtworks.go.config.commands.EntityConfigUpdateCommand;
import com.thoughtworks.go.config.remote.ConfigRepoConfig;
import com.thoughtworks.go.config.remote.PartialConfig;
import com.thoughtworks.go.domain.PipelineGroups;
import com.thoughtworks.go.helper.PipelineConfigMother;
import com.thoughtworks.go.listener.ConfigChangedListener;
import com.thoughtworks.go.listener.EntityConfigChangedListener;
import com.thoughtworks.go.server.domain.Username;
import com.thoughtworks.go.server.util.ServerVersion;
import com.thoughtworks.go.serverhealth.HealthStateScope;
import com.thoughtworks.go.serverhealth.ServerHealthService;
import com.thoughtworks.go.service.ConfigRepository;
import com.thoughtworks.go.util.*;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

import static com.thoughtworks.go.helper.ConfigFileFixture.CONFIG_WITH_1CONFIGREPO;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.*;

public class MergedGoConfigTest extends CachedGoConfigTestBase {

    private CachedFileGoConfig cachedFileGoConfig;

    private GoConfigPluginService configPluginService;
    private GoConfigWatchList configWatchList;
    private PartialConfigProvider plugin;

    private GoRepoConfigDataSource repoConfigDataSource;

    private GoPartialConfig partials;

    private File folder = new File("workdir");

    @Before
    public void setUp() throws Exception {
        configHelper = new GoConfigFileHelper(CONFIG_WITH_1CONFIGREPO);
        SystemEnvironment env = new SystemEnvironment();
        ConfigRepository configRepository = new ConfigRepository(env);
        configRepository.initialize();
        dataSource = new GoFileConfigDataSource(new DoNotUpgrade(), configRepository, env, new TimeProvider(),
                new ConfigCache(), new ServerVersion(), ConfigElementImplementationRegistryMother.withNoPlugins(),
                serverHealthService);
        serverHealthService = new ServerHealthService();
        cachedFileGoConfig = new CachedFileGoConfig(dataSource, serverHealthService);
        cachedFileGoConfig.loadConfigIfNull();

        configPluginService = mock(GoConfigPluginService.class);
        plugin = mock(PartialConfigProvider.class);
        when(configPluginService.partialConfigProviderFor(any(ConfigRepoConfig.class))).thenReturn(plugin);

        configWatchList = new GoConfigWatchList(cachedFileGoConfig);

        repoConfigDataSource = new GoRepoConfigDataSource(configWatchList,configPluginService,serverHealthService);

        partials = new GoPartialConfig(repoConfigDataSource, configWatchList);

        cachedGoConfig = new MergedGoConfig(serverHealthService, cachedFileGoConfig, partials);
        configHelper.usingCruiseConfigDao(new GoConfigDao(cachedFileGoConfig));
    }

    @Test
    public void shouldNotifyListenersWhenFileChanged() {
        ConfigChangedListener listener = mock(ConfigChangedListener.class);
        cachedGoConfig.registerListener(listener);
        verify(listener, times(1)).onConfigChange(any(CruiseConfig.class));

        String content = "<cruise schemaVersion='" + GoConstants.CONFIG_SCHEMA_VERSION + "'>\n"
                + "<server artifactsdir='artifacts' />"
                + "<pipelines>\n"
                + "</pipelines>\n"
                + "</cruise>";

        configHelper.writeXmlToConfigFile(content);

        // once during registerListener call, second when reloaded
        verify(listener, times(2)).onConfigChange(any(CruiseConfig.class));
    }

    @Test
    public void shouldNotifyConcernedListenersWhenEntityChanges() {
        final boolean[] pipelineConfigChangeListenerCalled = {false};
        final boolean[] agentConfigChangeListenerCalled = {false};
        final boolean[] cruiseConfigChangeListenerCalled = {false};
        EntityConfigChangedListener<PipelineConfig> pipelineConfigChangeListener = new EntityConfigChangedListener<PipelineConfig>() {
            @Override
            public void onEntityConfigChange(PipelineConfig entity) {
                pipelineConfigChangeListenerCalled[0] = true;
            }
        };
        EntityConfigChangedListener<AgentConfig> agentConfigChangeListener = new EntityConfigChangedListener<AgentConfig>() {
            @Override
            public void onEntityConfigChange(AgentConfig entity) {
                agentConfigChangeListenerCalled[0] = true;
            }
        };
        EntityConfigChangedListener<CruiseConfig> cruiseConfigChangeListener = new EntityConfigChangedListener<CruiseConfig>() {
            @Override
            public void onEntityConfigChange(CruiseConfig entity) {
                cruiseConfigChangeListenerCalled[0] = true;
            }
        };
        cachedGoConfig.registerListener(pipelineConfigChangeListener);
        cachedGoConfig.registerListener(agentConfigChangeListener);
        cachedGoConfig.registerListener(cruiseConfigChangeListener);

        EntityConfigUpdateCommand configCommand = mock(EntityConfigUpdateCommand.class);
        when(configCommand.isValid(any(CruiseConfig.class))).thenReturn(true);
        when(configCommand.getPreprocessedEntityConfig()).thenReturn(mock(PipelineConfig.class));
        cachedGoConfig.writeEntityWithLock(configCommand, new Username(new CaseInsensitiveString("user")));
        assertThat(pipelineConfigChangeListenerCalled[0], is(true));
        assertThat(agentConfigChangeListenerCalled[0], is(false));
        assertThat(cruiseConfigChangeListenerCalled[0], is(false));
    }

    @Test
    public void shouldListenForPartialChangesUponCreation() {
        assertTrue(partials.hasListener((MergedGoConfig) cachedGoConfig));
    }

    @Test
    public void shouldListenForFileChangesUponCreation() {
        assertTrue(cachedFileGoConfig.hasListener((MergedGoConfig) cachedGoConfig));
    }

    @Test
    public void shouldReturnMergedConfig_WhenThereIsValidPartialConfig() throws Exception {
        assertThat(configWatchList.getCurrentConfigRepos().size(), is(1));
        ConfigRepoConfig configRepo = configWatchList.getCurrentConfigRepos().get(0);
        PartialConfig part1 = new PartialConfig(new PipelineGroups(
                PipelineConfigMother.createGroup("part1", PipelineConfigMother.pipelineConfig("pipe1"))));
        when(plugin.load(any(File.class), any(PartialConfigLoadContext.class))).thenReturn(
                part1
        );
        repoConfigDataSource.onCheckoutComplete(configRepo.getMaterialConfig(), folder, "321e");
        assertThat(repoConfigDataSource.latestPartialConfigForMaterial(configRepo.getMaterialConfig()), is(part1));
        assertThat(cachedGoConfig.currentConfig().hasPipelineNamed(new CaseInsensitiveString("pipe1")), is(true));
    }

    @Test
    public void shouldNotifyWithMergedConfig_WhenPartUpdated() throws Exception {
        ConfigRepoConfig configRepo = configWatchList.getCurrentConfigRepos().get(0);
        PartialConfig part1 = new PartialConfig(new PipelineGroups(
                PipelineConfigMother.createGroup("part1", PipelineConfigMother.pipelineConfig("pipe1"))));
        when(plugin.load(any(File.class), any(PartialConfigLoadContext.class))).thenReturn(part1);

        ConfigChangedListener listener = mock(ConfigChangedListener.class);
        cachedGoConfig.registerListener(listener);
        // at registration
        verify(listener, times(1)).onConfigChange(any(CruiseConfig.class));

        repoConfigDataSource.onCheckoutComplete(configRepo.getMaterialConfig(), folder, "321e");

        assertThat("currentConfigShouldBeMerged",
                cachedGoConfig.currentConfig().hasPipelineNamed(new CaseInsensitiveString("pipe1")), is(true));
        verify(listener, times(2)).onConfigChange(any(CruiseConfig.class));
    }

    @Test
    public void shouldNotNotifyListenersWhenMergeFails() {
        ConfigRepoConfig configRepo = configWatchList.getCurrentConfigRepos().get(0);
        PartialConfig badPart = new PartialConfig(new PipelineGroups(
                PipelineConfigMother.createGroup("part1",
                        PipelineConfigMother.pipelineConfig("pipe1"), PipelineConfigMother.pipelineConfig("pipe1"))));
        when(plugin.load(any(File.class), any(PartialConfigLoadContext.class))).thenReturn(badPart);

        ConfigChangedListener listener = mock(ConfigChangedListener.class);
        cachedGoConfig.registerListener(listener);
        // at registration
        verify(listener, times(1)).onConfigChange(any(CruiseConfig.class));

        repoConfigDataSource.onCheckoutComplete(configRepo.getMaterialConfig(), folder, "321e");

        assertThat("currentConfigShouldBeMainXmlOnly",
                cachedGoConfig.currentConfig(), is(cachedFileGoConfig.currentConfig()));

        verify(listener, times(1)).onConfigChange(any(CruiseConfig.class));
    }

    @Test
    public void shouldSetErrorHealthStateWhenMergeFails() {
        ConfigRepoConfig configRepo = configWatchList.getCurrentConfigRepos().get(0);
        PartialConfig badPart = new PartialConfig(new PipelineGroups(
                PipelineConfigMother.createGroup("part1",
                        PipelineConfigMother.pipelineConfig("pipe1"), PipelineConfigMother.pipelineConfig("pipe1"))));
        when(plugin.load(any(File.class), any(PartialConfigLoadContext.class))).thenReturn(badPart);

        repoConfigDataSource.onCheckoutComplete(configRepo.getMaterialConfig(), folder, "321e");

        assertThat(serverHealthService.filterByScope(HealthStateScope.GLOBAL).isEmpty(), is(false));
        assertThat(serverHealthService.getLogsAsText().contains("Invalid Merged Config"), is(true));
        assertThat(serverHealthService.getLogsAsText().contains("pipe1"), is(true));
    }

    @Test
    public void shouldUnSetErrorHealthStateWhenMergePasses() {
        ConfigRepoConfig configRepo = configWatchList.getCurrentConfigRepos().get(0);
        PartialConfig badPart = new PartialConfig(new PipelineGroups(
                PipelineConfigMother.createGroup("part1",
                        PipelineConfigMother.pipelineConfig("pipe1"), PipelineConfigMother.pipelineConfig("pipe1"))));
        when(plugin.load(any(File.class), any(PartialConfigLoadContext.class))).thenReturn(badPart);

        repoConfigDataSource.onCheckoutComplete(configRepo.getMaterialConfig(), folder, "321e");

        assertThat(serverHealthService.getLogsAsText().contains("Invalid Merged Config"), is(true));

        //fix partial
        PartialConfig part1 = new PartialConfig(new PipelineGroups(
                PipelineConfigMother.createGroup("part1", PipelineConfigMother.pipelineConfig("pipe1"))));
        when(plugin.load(any(File.class), any(PartialConfigLoadContext.class))).thenReturn(part1);
        repoConfigDataSource.onCheckoutComplete(configRepo.getMaterialConfig(), folder, "321e");

        assertThat(serverHealthService.filterByScope(HealthStateScope.GLOBAL).isEmpty(), is(true));
    }

    @Test
    public void tryAssembleMergedConfig_shouldSetXmlConfigWhenNoPartials() {
        GoConfigHolder fileHolder = cachedFileGoConfig.loadConfigHolder();
        MergedGoConfig mergedGoConfig = (MergedGoConfig) this.cachedGoConfig;
        mergedGoConfig.tryAssembleMergedConfig(fileHolder, new ArrayList<PartialConfig>());
        assertSame(cachedFileGoConfig.loadConfigHolder(), mergedGoConfig.loadConfigHolder());
        assertSame(cachedFileGoConfig.currentConfig(), mergedGoConfig.currentConfig());
        assertSame(cachedFileGoConfig.loadForEditing(), mergedGoConfig.loadForEditing());
    }

    @Test
    public void tryAssembleMergedConfig_shouldSetNewConfigWithMergedConfigWhenThereArePartials() {
        GoConfigHolder fileHolder = cachedFileGoConfig.loadConfigHolder();
        MergedGoConfig mergedGoConfig = (MergedGoConfig) this.cachedGoConfig;
        PartialConfig part1 = new PartialConfig(new PipelineGroups(
                PipelineConfigMother.createGroup("part1", PipelineConfigMother.pipelineConfig("pipe1"))));
        mergedGoConfig.tryAssembleMergedConfig(fileHolder, Arrays.asList(part1));

        assertThat("configHolderHasRemotePartsOfConfigInConfig",
                mergedGoConfig.loadConfigHolder().config.hasPipelineNamed(new CaseInsensitiveString("pipe1")), is(true));
        assertThat("configHolderHasRemotePartsOfConfigInConfigForEdit",
                mergedGoConfig.loadConfigHolder().configForEdit.hasPipelineNamed(new CaseInsensitiveString("pipe1")), is(true));
        assertThat("currentConfigHasRemotePartsOfConfig",
                mergedGoConfig.currentConfig().hasPipelineNamed(new CaseInsensitiveString("pipe1")), is(true));
        assertThat("currentConfigForEditHasRemotePartsOfConfig",
                mergedGoConfig.loadForEditing().hasPipelineNamed(new CaseInsensitiveString("pipe1")), is(true));
        // yes, we really want to return un-editable pipeline in config for edit

        assertThat("configHolderHasLocalPartsOfConfigInConfig",
                mergedGoConfig.loadConfigHolder().config.getConfigRepos().size(), is(1));
        assertThat("configHolderHasLocalPartsOfConfigInConfigForEdit",
                mergedGoConfig.loadConfigHolder().configForEdit.getConfigRepos().size(), is(1));
        assertThat("currentConfigHasLocalPartsOfConfig",
                mergedGoConfig.currentConfig().getConfigRepos().size(), is(1));
        assertThat("currentConfigForEditHasLocalPartsOfConfig",
                mergedGoConfig.loadForEditing().getConfigRepos().size(), is(1));
    }

    @Test
    public void tryAssembleMergedConfig_shouldNotChangeConfigWhenMergeFailed() {
        GoConfigHolder fileHolder = cachedFileGoConfig.loadConfigHolder();
        MergedGoConfig mergedGoConfig = (MergedGoConfig) this.cachedGoConfig;
        PartialConfig badPart = new PartialConfig(new PipelineGroups(
                PipelineConfigMother.createGroup("part1",
                        PipelineConfigMother.pipelineConfig("pipe1"), PipelineConfigMother.pipelineConfig("pipe1"))));
        mergedGoConfig.tryAssembleMergedConfig(fileHolder, Arrays.asList(badPart));
        assertSame(cachedFileGoConfig.loadConfigHolder(), mergedGoConfig.loadConfigHolder());
        assertSame(cachedFileGoConfig.currentConfig(), mergedGoConfig.currentConfig());
        assertSame(cachedFileGoConfig.loadForEditing(), mergedGoConfig.loadForEditing());
    }

    @Test
    public void shouldDelegateWritePipelineConfigCallToFileService() {
        CachedFileGoConfig fileService = mock(CachedFileGoConfig.class);
        EntityConfigUpdateCommand saveCommand = mock(EntityConfigUpdateCommand.class);
        PipelineConfig pipelineConfig = new PipelineConfig();
        when(saveCommand.getPreprocessedEntityConfig()).thenReturn(pipelineConfig);
        MergedGoConfig mergedGoConfig = new MergedGoConfig(mock(ServerHealthService.class), fileService, mock(GoPartialConfig.class));
        EntityConfigSaveResult<PipelineConfig> saveResult = mock(EntityConfigSaveResult.class);
        GoConfigHolder savedConfig = new GoConfigHolder(new BasicCruiseConfig(), new BasicCruiseConfig());
        when(saveResult.getConfigHolder()).thenReturn(savedConfig);
        Username user = new Username(new CaseInsensitiveString("user"));
        when(fileService.writeEntityWithLock(eq(saveCommand), any(GoConfigHolder.class), eq(user))).thenReturn(saveResult);

        mergedGoConfig.writeEntityWithLock(saveCommand, user);
        assertThat(mergedGoConfig.loadConfigHolder(), is(savedConfig));
        assertThat(mergedGoConfig.currentConfig(), is(savedConfig.config));
        assertThat(mergedGoConfig.loadForEditing(), is(savedConfig.configForEdit));
        verify(fileService).writeEntityWithLock(eq(saveCommand), any(GoConfigHolder.class), eq(user));
    }
}
