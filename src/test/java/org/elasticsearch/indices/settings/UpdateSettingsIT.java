/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.indices.settings;

import org.elasticsearch.action.admin.cluster.health.ClusterHealthResponse;
import org.elasticsearch.action.admin.cluster.node.stats.NodeStats;
import org.elasticsearch.action.admin.cluster.node.stats.NodesStatsResponse;
import org.elasticsearch.action.admin.indices.settings.get.GetSettingsResponse;
import org.elasticsearch.cluster.metadata.IndexMetaData;
import org.elasticsearch.common.Priority;
import org.elasticsearch.common.settings.Setting;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.IndexModule;
import org.elasticsearch.index.IndexService;
import org.elasticsearch.index.MergePolicyConfig;
import org.elasticsearch.index.MergeSchedulerConfig;
import org.elasticsearch.index.engine.VersionConflictEngineException;
import org.elasticsearch.index.store.IndexStore;
import org.elasticsearch.index.store.Store;
import org.elasticsearch.indices.IndicesService;
import org.elasticsearch.plugins.Plugin;
import org.elasticsearch.test.ESIntegTestCase;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_BLOCKS_METADATA;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_BLOCKS_READ;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_BLOCKS_WRITE;
import static org.elasticsearch.cluster.metadata.IndexMetaData.SETTING_READ_ONLY;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertAcked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertBlocked;
import static org.elasticsearch.test.hamcrest.ElasticsearchAssertions.assertThrows;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

public class UpdateSettingsIT extends ESIntegTestCase {
    public void testInvalidUpdateOnClosedIndex() {
        createIndex("test");
        assertAcked(client().admin().indices().prepareClose("test").get());
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class, () ->
            client()
                .admin()
                .indices()
                .prepareUpdateSettings("test")
                .setSettings(Settings.builder().put("index.analysis.char_filter.invalid_char.type", "invalid"))
                .get());
        assertEquals(exception.getMessage(), "Unknown char_filter type [invalid] for [invalid_char]");
    }

    public void testInvalidDynamicUpdate() {
        createIndex("test");
        IllegalArgumentException exception = expectThrows(IllegalArgumentException.class, () ->
            client()
                .admin()
                .indices()
                .prepareUpdateSettings("test")
                .setSettings(Settings.builder().put("index.dummy", "boom"))
                .execute()
                .actionGet());
        assertEquals(exception.getCause().getMessage(), "this setting goes boom");
        IndexMetaData indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertNotEquals(indexMetaData.getSettings().get("index.dummy"), "invalid dynamic value");
    }

    @Override
    protected Collection<Class<? extends Plugin>> nodePlugins() {
        return Arrays.asList(DummySettingPlugin.class, FinalSettingPlugin.class);
    }

    public static class DummySettingPlugin extends Plugin {
        public static final Setting<String> DUMMY_SETTING = Setting.simpleString("index.dummy",
            Setting.Property.IndexScope, Setting.Property.Dynamic);
        @Override
        public void onIndexModule(IndexModule indexModule) {
            indexModule.addSettingsUpdateConsumer(DUMMY_SETTING, (s) -> {}, (s) -> {
                if (s.equals("boom"))
                    throw new IllegalArgumentException("this setting goes boom");
            });
        }

        @Override
        public List<Setting<?>> getSettings() {
            return Collections.singletonList(DUMMY_SETTING);
        }
    }

    public static class FinalSettingPlugin extends Plugin {
        public static final Setting<String> FINAL_SETTING = Setting.simpleString("index.final",
            Setting.Property.IndexScope, Setting.Property.Final);
        @Override
        public void onIndexModule(IndexModule indexModule) {
        }

        @Override
        public List<Setting<?>> getSettings() {
            return Collections.singletonList(FINAL_SETTING);
        }
    }

    public void testResetDefault() {
        createIndex("test");

        client()
            .admin()
            .indices()
            .prepareUpdateSettings("test")
            .setSettings(Settings.builder().put("index.refresh_interval", -1).put("index.translog.flush_threshold_size", "1024b"))
            .execute()
            .actionGet();
        IndexMetaData indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertEquals(indexMetaData.getSettings().get("index.refresh_interval"), "-1");
        for (IndicesService service : internalCluster().getInstances(IndicesService.class)) {
            IndexService indexService = service.indexService(resolveIndex("test"));
            if (indexService != null) {
                assertEquals(indexService.getIndexSettings().getRefreshInterval().millis(), -1);
                assertEquals(indexService.getIndexSettings().getFlushThresholdSize().getBytes(), 1024);
            }
        }
        client()
            .admin()
            .indices()
            .prepareUpdateSettings("test")
            .setSettings(Settings.builder().putNull("index.refresh_interval"))
            .execute()
            .actionGet();
        indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertNull(indexMetaData.getSettings().get("index.refresh_interval"));
        for (IndicesService service : internalCluster().getInstances(IndicesService.class)) {
            IndexService indexService = service.indexService(resolveIndex("test"));
            if (indexService != null) {
                assertEquals(indexService.getIndexSettings().getRefreshInterval().millis(), 1000);
                assertEquals(indexService.getIndexSettings().getFlushThresholdSize().getBytes(), 1024);
            }
        }
    }
    public void testOpenCloseUpdateSettings() throws Exception {
        createIndex("test");
        expectThrows(IllegalArgumentException.class, () ->
            client()
                .admin()
                .indices()
                .prepareUpdateSettings("test")
                .setSettings(Settings.builder()
                    .put("index.refresh_interval", -1) // this one can change
                    .put("index.fielddata.cache", "none")) // this one can't
                .execute()
                .actionGet()
        );
        expectThrows(IllegalArgumentException.class, () ->
            client()
                .admin()
                .indices()
                .prepareUpdateSettings("test")
                .setSettings(Settings.builder()
                    .put("index.refresh_interval", -1) // this one can change
                    .put("index.final", "no")) // this one can't
                .execute()
                .actionGet()
        );
        IndexMetaData indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertThat(indexMetaData.getSettings().get("index.refresh_interval"), nullValue());
        assertThat(indexMetaData.getSettings().get("index.fielddata.cache"), nullValue());
        assertThat(indexMetaData.getSettings().get("index.final"), nullValue());

        // Now verify via dedicated get settings api:
        GetSettingsResponse getSettingsResponse = client().admin().indices().prepareGetSettings("test").get();
        assertThat(getSettingsResponse.getSetting("test", "index.refresh_interval"), nullValue());
        assertThat(getSettingsResponse.getSetting("test", "index.fielddata.cache"), nullValue());
        assertThat(getSettingsResponse.getSetting("test", "index.final"), nullValue());

        client()
            .admin()
            .indices()
            .prepareUpdateSettings("test")
            .setSettings(Settings.builder().put("index.refresh_interval", -1)) // this one can change
            .execute()
            .actionGet();

        indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertThat(indexMetaData.getSettings().get("index.refresh_interval"), equalTo("-1"));
        // Now verify via dedicated get settings api:
        getSettingsResponse = client().admin().indices().prepareGetSettings("test").get();
        assertThat(getSettingsResponse.getSetting("test", "index.refresh_interval"), equalTo("-1"));

        // now close the index, change the non dynamic setting, and see that it applies

        // Wait for the index to turn green before attempting to close it
        ClusterHealthResponse health =
            client()
                .admin()
                .cluster()
                .prepareHealth()
                .setTimeout("30s")
                .setWaitForEvents(Priority.LANGUID)
                .setWaitForGreenStatus()
                .execute()
                .actionGet();
        assertThat(health.isTimedOut(), equalTo(false));

        client().admin().indices().prepareClose("test").execute().actionGet();

        try {
            client()
                .admin()
                .indices()
                .prepareUpdateSettings("test")
                .setSettings(Settings.builder().put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, 1))
                .execute()
                .actionGet();
            fail("can't change number of replicas on a closed index");
        } catch (IllegalArgumentException ex) {
            assertTrue(ex.getMessage(), ex.getMessage().startsWith("Can't update [index.number_of_replicas] on closed indices [[test/"));
            assertTrue(ex.getMessage(), ex.getMessage().endsWith("]] - can leave index in an unopenable state"));
            // expected
        }
        client()
            .admin()
            .indices()
            .prepareUpdateSettings("test")
            .setSettings(Settings.builder()
                .put("index.refresh_interval", "1s") // this one can change
                .put("index.fielddata.cache", "none")) // this one can't
            .execute()
            .actionGet();

        indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertThat(indexMetaData.getSettings().get("index.refresh_interval"), equalTo("1s"));
        assertThat(indexMetaData.getSettings().get("index.fielddata.cache"), equalTo("none"));

        IllegalArgumentException ex = expectThrows(IllegalArgumentException.class, () ->
            client()
                .admin()
                .indices()
                .prepareUpdateSettings("test")
                .setSettings(Settings.builder()
                    .put("index.refresh_interval", -1) // this one can change
                    .put("index.final", "no")) // this one really can't
                .execute()
                .actionGet()
        );
        assertThat(ex.getMessage(), containsString("final test setting [index.final], not updateable"));
        indexMetaData = client().admin().cluster().prepareState().execute().actionGet().getState().metaData().index("test");
        assertThat(indexMetaData.getSettings().get("index.refresh_interval"), equalTo("1s"));
        assertThat(indexMetaData.getSettings().get("index.final"), nullValue());


        // Now verify via dedicated get settings api:
        getSettingsResponse = client().admin().indices().prepareGetSettings("test").get();
        assertThat(getSettingsResponse.getSetting("test", "index.refresh_interval"), equalTo("1s"));
        assertThat(getSettingsResponse.getSetting("test", "index.final"), nullValue());
    }

    public void testEngineGCDeletesSetting() throws InterruptedException {
        createIndex("test");
        client().prepareIndex("test", "type", "1").setSource("f", 1).get(); // set version to 1
        client().prepareDelete("test", "type", "1").get(); // sets version to 2
        // delete is still in cache this should work & set version to 3
        client().prepareIndex("test", "type", "1").setSource("f", 2).setVersion(2).get();
        client().admin().indices().prepareUpdateSettings("test").setSettings(Settings.builder().put("index.gc_deletes", 0)).get();

        client().prepareDelete("test", "type", "1").get(); // sets version to 4
        Thread.sleep(300); // wait for cache time to change TODO: this needs to be solved better. To be discussed.
        // delete is should not be in cache
        assertThrows(client().prepareIndex("test", "type", "1").setSource("f", 3).setVersion(4), VersionConflictEngineException.class);

    }

    // #6626: make sure we can update throttle settings and the changes take effect
    public void testUpdateThrottleSettings() {
        // No throttling at first, only 1 non-replicated shard, force lots of merging:
        assertAcked(prepareCreate("test")
                    .setSettings(Settings.builder()
                                 .put(IndexStore.INDEX_STORE_THROTTLE_TYPE_SETTING.getKey(), "none")
                                 .put(IndexMetaData.SETTING_NUMBER_OF_SHARDS, "1")
                                 .put(IndexMetaData.SETTING_NUMBER_OF_REPLICAS, "0")
                                 .put(MergePolicyConfig.INDEX_MERGE_POLICY_MAX_MERGE_AT_ONCE_SETTING.getKey(), "2")
                                 .put(MergePolicyConfig.INDEX_MERGE_POLICY_SEGMENTS_PER_TIER_SETTING.getKey(), "2")
                                 .put(MergeSchedulerConfig.MAX_THREAD_COUNT_SETTING.getKey(), "1")
                                 .put(MergeSchedulerConfig.MAX_MERGE_COUNT_SETTING.getKey(), "2")
                                 .put(Store.INDEX_STORE_STATS_REFRESH_INTERVAL_SETTING.getKey(), 0) // get stats all the time - no caching
                                 ));
        ensureGreen();
        long termUpto = 0;
        for(int i=0;i<100;i++) {
            // Provoke slowish merging by making many unique terms:
            StringBuilder sb = new StringBuilder();
            for(int j=0;j<100;j++) {
                sb.append(' ');
                sb.append(termUpto++);
            }
            client().prepareIndex("test", "type", ""+termUpto).setSource("field" + (i%10), sb.toString()).get();
            if (i % 2 == 0) {
                refresh();
            }
        }

        // No merge IO throttling should have happened:
        NodesStatsResponse nodesStats = client().admin().cluster().prepareNodesStats().setIndices(true).get();
        for(NodeStats stats : nodesStats.getNodes()) {
            assertThat(stats.getIndices().getStore().getThrottleTime().getMillis(), equalTo(0L));
        }

        logger.info("test: set low merge throttling");

        // Now updates settings to turn on merge throttling lowish rate
        client()
            .admin()
            .indices()
            .prepareUpdateSettings("test")
            .setSettings(
                Settings.builder()
                    .put(IndexStore.INDEX_STORE_THROTTLE_TYPE_SETTING.getKey(), "merge")
                    .put(IndexStore.INDEX_STORE_THROTTLE_MAX_BYTES_PER_SEC_SETTING.getKey(), "1mb"))
            .get();

        // Make sure setting says it is in fact changed:
        GetSettingsResponse getSettingsResponse = client().admin().indices().prepareGetSettings("test").get();
        assertThat(getSettingsResponse.getSetting("test", IndexStore.INDEX_STORE_THROTTLE_TYPE_SETTING.getKey()), equalTo("merge"));

        // Also make sure we see throttling kicking in:
        boolean done = false;
        while (done == false) {
            // Provoke slowish merging by making many unique terms:
            for(int i=0;i<5;i++) {
                StringBuilder sb = new StringBuilder();
                for(int j=0;j<100;j++) {
                    sb.append(' ');
                    sb.append(termUpto++);
                    sb.append(" some random text that keeps repeating over and over again hambone");
                }
                client().prepareIndex("test", "type", ""+termUpto).setSource("field" + (i%10), sb.toString()).get();
            }
            refresh();
            nodesStats = client().admin().cluster().prepareNodesStats().setIndices(true).get();
            for(NodeStats stats : nodesStats.getNodes()) {
                long throttleMillis = stats.getIndices().getStore().getThrottleTime().getMillis();
                if (throttleMillis > 0) {
                    done = true;
                    break;
                }
            }
        }

        logger.info("test: disable merge throttling");

        // Now updates settings to disable merge throttling
        client()
            .admin()
            .indices()
            .prepareUpdateSettings("test")
            .setSettings(Settings.builder().put(IndexStore.INDEX_STORE_THROTTLE_TYPE_SETTING.getKey(), "none"))
            .get();

        // Optimize does a waitForMerges, which we must do to make sure all in-flight (throttled) merges finish:
        logger.info("test: optimize");
        client().admin().indices().prepareForceMerge("test").setMaxNumSegments(1).get();
        logger.info("test: optimize done");

        // Record current throttling so far
        long sumThrottleTime = 0;
        nodesStats = client().admin().cluster().prepareNodesStats().setIndices(true).get();
        for(NodeStats stats : nodesStats.getNodes()) {
            sumThrottleTime += stats.getIndices().getStore().getThrottleTime().getMillis();
        }

        // Make sure no further throttling happens:
        for(int i=0;i<100;i++) {
            // Provoke slowish merging by making many unique terms:
            StringBuilder sb = new StringBuilder();
            for(int j=0;j<100;j++) {
                sb.append(' ');
                sb.append(termUpto++);
            }
            client().prepareIndex("test", "type", ""+termUpto).setSource("field" + (i%10), sb.toString()).get();
            if (i % 2 == 0) {
                refresh();
            }
        }
        logger.info("test: done indexing after disabling throttling");

        long newSumThrottleTime = 0;
        nodesStats = client().admin().cluster().prepareNodesStats().setIndices(true).get();
        for(NodeStats stats : nodesStats.getNodes()) {
            newSumThrottleTime += stats.getIndices().getStore().getThrottleTime().getMillis();
        }

        // No additional merge IO throttling should have happened:
        assertEquals(sumThrottleTime, newSumThrottleTime);

        // Optimize & flush and wait; else we sometimes get a "Delete Index failed - not acked"
        // when ESIntegTestCase.after tries to remove indices created by the test:

        // Wait for merges to finish
        client().admin().indices().prepareForceMerge("test").get();
        flush();

        logger.info("test: test done");
    }

    public void testUpdateSettingsWithBlocks() {
        createIndex("test");
        ensureGreen("test");

        Settings.Builder builder = Settings.builder().put("index.refresh_interval", -1);

        for (String blockSetting : Arrays.asList(SETTING_BLOCKS_READ, SETTING_BLOCKS_WRITE)) {
            try {
                enableIndexBlock("test", blockSetting);
                assertAcked(client().admin().indices().prepareUpdateSettings("test").setSettings(builder));
            } finally {
                disableIndexBlock("test", blockSetting);
            }
        }

        // Closing an index is blocked
        for (String blockSetting : Arrays.asList(SETTING_READ_ONLY, SETTING_BLOCKS_METADATA)) {
            try {
                enableIndexBlock("test", blockSetting);
                assertBlocked(client().admin().indices().prepareUpdateSettings("test").setSettings(builder));
            } finally {
                disableIndexBlock("test", blockSetting);
            }
        }
    }

}
