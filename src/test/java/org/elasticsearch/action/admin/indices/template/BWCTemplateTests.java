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

package org.elasticsearch.action.admin.indices.template;

import org.elasticsearch.common.xcontent.XContentType;
import org.elasticsearch.test.ESSingleNodeTestCase;

import static org.elasticsearch.test.StreamsUtils.copyToBytesFromClasspath;

/**
 * Rudimentary tests that the templates used by Logstash and Beats
 * prior to their 5.x releases work for newly created indices
 */
public class BWCTemplateTests extends ESSingleNodeTestCase {
    public void testBeatsTemplatesBWC() throws Exception {
        byte[] topBeat = copyToBytesFromClasspath("/org/elasticsearch/action/admin/indices/template/topbeat-1.3.template.json");
        byte[] packetBeat = copyToBytesFromClasspath("/org/elasticsearch/action/admin/indices/template/packetbeat-1.3.template.json");
        byte[] fileBeat = copyToBytesFromClasspath("/org/elasticsearch/action/admin/indices/template/filebeat-1.3.template.json");
        byte[] winLogBeat = copyToBytesFromClasspath("/org/elasticsearch/action/admin/indices/template/winlogbeat-1.3.template.json");
        client().admin().indices().preparePutTemplate("topbeat").setSource(topBeat, XContentType.JSON).get();
        client().admin().indices().preparePutTemplate("packetbeat").setSource(packetBeat, XContentType.JSON).get();
        client().admin().indices().preparePutTemplate("filebeat").setSource(fileBeat, XContentType.JSON).get();
        client().admin().indices().preparePutTemplate("winlogbeat").setSource(winLogBeat, XContentType.JSON).get();

        client().prepareIndex("topbeat-foo", "doc", "1").setSource("message", "foo").get();
        client().prepareIndex("packetbeat-foo", "doc", "1").setSource("message", "foo").get();
        client().prepareIndex("filebeat-foo", "doc", "1").setSource("message", "foo").get();
        client().prepareIndex("winlogbeat-foo", "doc", "1").setSource("message", "foo").get();
    }

    public void testLogstashTemplatesBWC() throws Exception {
        byte[] ls2x = copyToBytesFromClasspath("/org/elasticsearch/action/admin/indices/template/logstash-2.x.template.json");
        byte[] ls5x = copyToBytesFromClasspath("/org/elasticsearch/action/admin/indices/template/logstash-5.x.template.json");
        client().admin().indices().preparePutTemplate("logstash-2x").setSource(ls2x, XContentType.JSON).get();
        client().prepareIndex("logstash-foo", "doc", "1").setSource("message", "foo").get();
        client().admin().indices().prepareDeleteTemplate("*").get();

        client().admin().indices().preparePutTemplate("logstash-5x").setSource(ls5x, XContentType.JSON).get();
        client().prepareIndex("logstash-foo", "doc", "1").setSource("message", "foo").get();
    }

}
