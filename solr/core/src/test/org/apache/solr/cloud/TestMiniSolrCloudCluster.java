/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.cloud;

import java.io.File;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import com.carrotsearch.randomizedtesting.rules.SystemPropertiesRestoreRule;
import org.apache.lucene.util.LuceneTestCase;
import org.apache.lucene.util.LuceneTestCase.SuppressSysoutChecks;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.client.solrj.SolrQuery;
import org.apache.solr.client.solrj.embedded.JettyConfig;
import org.apache.solr.client.solrj.embedded.JettyConfig.Builder;
import org.apache.solr.client.solrj.embedded.JettySolrRunner;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.apache.solr.client.solrj.response.QueryResponse;
import org.apache.solr.client.solrj.response.RequestStatusState;
import org.apache.solr.common.SolrInputDocument;
import org.apache.solr.common.SolrDocument;
import org.apache.solr.common.cloud.ClusterState;
import org.apache.solr.common.cloud.Replica;
import org.apache.solr.common.cloud.Slice;
import org.apache.solr.common.cloud.SolrZkClient;
import org.apache.solr.common.cloud.ZkStateReader;
import org.apache.solr.common.params.CommonParams;
import org.apache.solr.common.params.ShardParams;
import org.apache.solr.common.util.SimpleOrderedMap;
import org.apache.solr.core.CoreDescriptor;
import org.apache.solr.response.SolrQueryResponse;
import org.apache.solr.util.RevertDefaultThreadHandlerRule;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.rules.TestRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Test of the MiniSolrCloudCluster functionality. Keep in mind, 
 * MiniSolrCloudCluster is designed to be used outside of the Lucene test
 * hierarchy.
 */
@SuppressSysoutChecks(bugUrl = "Solr logs to JUL")
public class TestMiniSolrCloudCluster extends LuceneTestCase {

  private static final Logger log = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());
  protected int NUM_SERVERS = 5;
  protected int NUM_SHARDS = 2;
  protected int REPLICATION_FACTOR = 2;

  public TestMiniSolrCloudCluster () {
    NUM_SERVERS = 5;
    NUM_SHARDS = 2;
    REPLICATION_FACTOR = 2;
  }
  
  @Rule
  public TestRule solrTestRules = RuleChain
      .outerRule(new SystemPropertiesRestoreRule());
  
  @ClassRule
  public static TestRule solrClassRules = RuleChain.outerRule(
      new SystemPropertiesRestoreRule()).around(
      new RevertDefaultThreadHandlerRule());
  
  private MiniSolrCloudCluster createMiniSolrCloudCluster() throws Exception {
    Builder jettyConfig = JettyConfig.builder();
    jettyConfig.waitForLoadingCoresToFinish(null);
    return new MiniSolrCloudCluster(NUM_SERVERS, createTempDir(), jettyConfig.build());
  }
    
  private void createCollection(MiniSolrCloudCluster miniCluster, String collectionName, String createNodeSet, String asyncId,
      Boolean indexToPersist, Map<String,String> collectionProperties) throws Exception {
    String configName = "solrCloudCollectionConfig";
    File configDir = new File(SolrTestCaseJ4.TEST_HOME() + File.separator + "collection1" + File.separator + "conf");
    miniCluster.uploadConfigDir(configDir, configName);

    final boolean persistIndex = (indexToPersist != null ? indexToPersist.booleanValue() : random().nextBoolean());
    if (collectionProperties == null) {
      collectionProperties = new HashMap<>();
    }
    collectionProperties.putIfAbsent(CoreDescriptor.CORE_CONFIG, "solrconfig-tlog.xml");
    collectionProperties.putIfAbsent("solr.tests.maxBufferedDocs", "100000");
    collectionProperties.putIfAbsent("solr.tests.ramBufferSizeMB", "100");
    // use non-test classes so RandomizedRunner isn't necessary
    collectionProperties.putIfAbsent("solr.tests.mergePolicy", "org.apache.lucene.index.TieredMergePolicy");
    collectionProperties.putIfAbsent("solr.tests.mergeScheduler", "org.apache.lucene.index.ConcurrentMergeScheduler");
    collectionProperties.putIfAbsent("solr.directoryFactory", (persistIndex ? "solr.StandardDirectoryFactory" : "solr.RAMDirectoryFactory"));
    
    miniCluster.createCollection(collectionName, NUM_SHARDS, REPLICATION_FACTOR, configName, createNodeSet, asyncId, collectionProperties);
  }

  @Test
  public void testCollectionCreateSearchDelete() throws Exception {

    final String collectionName = "testcollection";
    MiniSolrCloudCluster miniCluster = createMiniSolrCloudCluster();

    final CloudSolrClient cloudSolrClient = miniCluster.getSolrClient();

    try {
      assertNotNull(miniCluster.getZkServer());
      List<JettySolrRunner> jettys = miniCluster.getJettySolrRunners();
      assertEquals(NUM_SERVERS, jettys.size());
      for (JettySolrRunner jetty : jettys) {
        assertTrue(jetty.isRunning());
      }

      // shut down a server
      log.info("#### Stopping a server");
      JettySolrRunner stoppedServer = miniCluster.stopJettySolrRunner(0);
      assertTrue(stoppedServer.isStopped());
      assertEquals(NUM_SERVERS - 1, miniCluster.getJettySolrRunners().size());

      // create a server
      log.info("#### Starting a server");
      JettySolrRunner startedServer = miniCluster.startJettySolrRunner();
      assertTrue(startedServer.isRunning());
      assertEquals(NUM_SERVERS, miniCluster.getJettySolrRunners().size());

      // create collection
      log.info("#### Creating a collection");
      final String asyncId = (random().nextBoolean() ? null : "asyncId("+collectionName+".create)="+random().nextInt());
      createCollection(miniCluster, collectionName, null, asyncId, null, null);
      if (asyncId != null) {
        final RequestStatusState state = AbstractFullDistribZkTestBase.getRequestStateAfterCompletion(asyncId, 330,
            cloudSolrClient);
        assertSame("did not see async createCollection completion", RequestStatusState.COMPLETED, state);
      }

      ZkStateReader zkStateReader = miniCluster.getSolrClient().getZkStateReader();
      AbstractDistribZkTestBase.waitForRecoveriesToFinish(collectionName, zkStateReader, true, true, 330);

      // modify/query collection
      log.info("#### updating a querying collection");
      cloudSolrClient.setDefaultCollection(collectionName);
      SolrInputDocument doc = new SolrInputDocument();
      doc.setField("id", "1");
      cloudSolrClient.add(doc);
      cloudSolrClient.commit();
      SolrQuery query = new SolrQuery();
      query.setQuery("*:*");
      QueryResponse rsp = cloudSolrClient.query(query);
      assertEquals(1, rsp.getResults().getNumFound());

      // remove a server not hosting any replicas
      zkStateReader.updateClusterState();
      ClusterState clusterState = zkStateReader.getClusterState();
      HashMap<String, JettySolrRunner> jettyMap = new HashMap<String, JettySolrRunner>();
      for (JettySolrRunner jetty : miniCluster.getJettySolrRunners()) {
        String key = jetty.getBaseUrl().toString().substring((jetty.getBaseUrl().getProtocol() + "://").length());
        jettyMap.put(key, jetty);
      }
      Collection<Slice> slices = clusterState.getSlices(collectionName);
      // track the servers not host repliacs
      for (Slice slice : slices) {
        jettyMap.remove(slice.getLeader().getNodeName().replace("_solr", "/solr"));
        for (Replica replica : slice.getReplicas()) {
          jettyMap.remove(replica.getNodeName().replace("_solr", "/solr"));
        }
      }
      assertTrue("Expected to find a node without a replica", jettyMap.size() > 0);
      log.info("#### Stopping a server");
      JettySolrRunner jettyToStop = jettyMap.entrySet().iterator().next().getValue();
      jettys = miniCluster.getJettySolrRunners();
      for (int i = 0; i < jettys.size(); ++i) {
        if (jettys.get(i).equals(jettyToStop)) {
          miniCluster.stopJettySolrRunner(i);
          assertEquals(NUM_SERVERS - 1, miniCluster.getJettySolrRunners().size());
        }
      }

      // re-create a server (to restore original NUM_SERVERS count)
      log.info("#### Starting a server");
      startedServer = miniCluster.startJettySolrRunner(jettyToStop);
      assertTrue(startedServer.isRunning());
      assertEquals(NUM_SERVERS, miniCluster.getJettySolrRunners().size());


      // delete the collection we created earlier
      miniCluster.deleteCollection(collectionName);
      AbstractDistribZkTestBase.waitForCollectionToDisappear(collectionName, zkStateReader, true, true, 330);

      // create it again
      String asyncId2 = (random().nextBoolean() ? null : "asyncId("+collectionName+".create)="+random().nextInt());
      createCollection(miniCluster, collectionName, null, asyncId2, null, null);
      if (asyncId2 != null) {
        final RequestStatusState state = AbstractFullDistribZkTestBase.getRequestStateAfterCompletion(asyncId2, 330,
            cloudSolrClient);
        assertSame("did not see async createCollection completion", RequestStatusState.COMPLETED, state);
      }
      AbstractDistribZkTestBase.waitForRecoveriesToFinish(collectionName, zkStateReader, true, true, 330);

      // check that there's no left-over state
      assertEquals(0, cloudSolrClient.query(new SolrQuery("*:*")).getResults().getNumFound());
      cloudSolrClient.add(doc);
      cloudSolrClient.commit();
      assertEquals(1, cloudSolrClient.query(new SolrQuery("*:*")).getResults().getNumFound());

    }
    finally {
      miniCluster.shutdown();
    }
  }

  @Test
  public void testErrorsInStartup() throws Exception {

    AtomicInteger jettyIndex = new AtomicInteger();

    MiniSolrCloudCluster cluster = null;
    try {
      cluster = new MiniSolrCloudCluster(3, createTempDir(), JettyConfig.builder().build()) {
        @Override
        public JettySolrRunner startJettySolrRunner(String name, String context, JettyConfig config) throws Exception {
          if (jettyIndex.incrementAndGet() != 2)
            return super.startJettySolrRunner(name, context, config);
          throw new IOException("Fake exception on startup!");
        }
      };
      fail("Expected an exception to be thrown from MiniSolrCloudCluster");
    }
    catch (Exception e) {
      assertEquals("Error starting up MiniSolrCloudCluster", e.getMessage());
      assertEquals("Expected one suppressed exception", 1, e.getSuppressed().length);
      assertEquals("Fake exception on startup!", e.getSuppressed()[0].getMessage());
    }
    finally {
      if (cluster != null)
        cluster.shutdown();
    }
  }

  @Test
  public void testErrorsInShutdown() throws Exception {

    AtomicInteger jettyIndex = new AtomicInteger();

    MiniSolrCloudCluster cluster = new MiniSolrCloudCluster(3, createTempDir(), JettyConfig.builder().build()) {
        @Override
        protected JettySolrRunner stopJettySolrRunner(JettySolrRunner jetty) throws Exception {
          JettySolrRunner j = super.stopJettySolrRunner(jetty);
          if (jettyIndex.incrementAndGet() == 2)
            throw new IOException("Fake IOException on shutdown!");
          return j;
        }
      };

    try {
      cluster.shutdown();
      fail("Expected an exception to be thrown on MiniSolrCloudCluster shutdown");
    }
    catch (Exception e) {
      assertEquals("Error shutting down MiniSolrCloudCluster", e.getMessage());
      assertEquals("Expected one suppressed exception", 1, e.getSuppressed().length);
      assertEquals("Fake IOException on shutdown!", e.getSuppressed()[0].getMessage());
    }

  }

  @Test
  public void testExtraFilters() throws Exception {
    Builder jettyConfig = JettyConfig.builder();
    jettyConfig.waitForLoadingCoresToFinish(null);
    jettyConfig.withFilter(JettySolrRunner.DebugFilter.class, "*");
    MiniSolrCloudCluster cluster = new MiniSolrCloudCluster(NUM_SERVERS, createTempDir(), jettyConfig.build());
    cluster.shutdown();
  }

  @Test
  public void testCollectionCreateWithoutCoresThenDelete() throws Exception {

    final String collectionName = "testSolrCloudCollectionWithoutCores";
    final MiniSolrCloudCluster miniCluster = createMiniSolrCloudCluster();
    final CloudSolrClient cloudSolrClient = miniCluster.getSolrClient();

    try {
      assertNotNull(miniCluster.getZkServer());
      assertFalse(miniCluster.getJettySolrRunners().isEmpty());

      // create collection
      final String asyncId = (random().nextBoolean() ? null : "asyncId("+collectionName+".create)="+random().nextInt());
      createCollection(miniCluster, collectionName, OverseerCollectionMessageHandler.CREATE_NODE_SET_EMPTY, asyncId, null, null);
      if (asyncId != null) {
        final RequestStatusState state = AbstractFullDistribZkTestBase.getRequestStateAfterCompletion(asyncId, 330, cloudSolrClient);
        assertSame("did not see async createCollection completion", RequestStatusState.COMPLETED, state);
      }

      try (SolrZkClient zkClient = new SolrZkClient
          (miniCluster.getZkServer().getZkAddress(), AbstractZkTestCase.TIMEOUT, AbstractZkTestCase.TIMEOUT, null);
          ZkStateReader zkStateReader = new ZkStateReader(zkClient)) {
        
        // wait for collection to appear
        AbstractDistribZkTestBase.waitForRecoveriesToFinish(collectionName, zkStateReader, true, true, 330);

        // check the collection's corelessness
        {
          int coreCount = 0; 
          for (Map.Entry<String,Slice> entry : zkStateReader.getClusterState().getSlicesMap(collectionName).entrySet()) {
            coreCount += entry.getValue().getReplicasMap().entrySet().size();
          }
          assertEquals(0, coreCount);
        }
        
        // delete the collection we created earlier
        miniCluster.deleteCollection(collectionName);
        AbstractDistribZkTestBase.waitForCollectionToDisappear(collectionName, zkStateReader, true, true, 330);    
      }
    }
    finally {
      miniCluster.shutdown();
    }
  }

  @Test
  public void testStopAllStartAll() throws Exception {

    final String collectionName = "testStopAllStartAllCollection";

    final MiniSolrCloudCluster miniCluster = createMiniSolrCloudCluster();

    try {
      assertNotNull(miniCluster.getZkServer());
      List<JettySolrRunner> jettys = miniCluster.getJettySolrRunners();
      assertEquals(NUM_SERVERS, jettys.size());
      for (JettySolrRunner jetty : jettys) {
        assertTrue(jetty.isRunning());
      }

      createCollection(miniCluster, collectionName, null, null, Boolean.TRUE, null);
      final CloudSolrClient cloudSolrClient = miniCluster.getSolrClient();
      cloudSolrClient.setDefaultCollection(collectionName);
      final SolrQuery query = new SolrQuery("*:*");
      final SolrInputDocument doc = new SolrInputDocument();

      try (SolrZkClient zkClient = new SolrZkClient
          (miniCluster.getZkServer().getZkAddress(), AbstractZkTestCase.TIMEOUT, AbstractZkTestCase.TIMEOUT, null);
          ZkStateReader zkStateReader = new ZkStateReader(zkClient)) {
        AbstractDistribZkTestBase.waitForRecoveriesToFinish(collectionName, zkStateReader, true, true, 330);

        // modify collection
        final int numDocs = 1 + random().nextInt(10);
        for (int ii = 1; ii <= numDocs; ++ii) {
          doc.setField("id", ""+ii);
          cloudSolrClient.add(doc);
          if (ii*2 == numDocs) cloudSolrClient.commit();
        }
        cloudSolrClient.commit();
        // query collection
        {
          final QueryResponse rsp = cloudSolrClient.query(query);
          assertEquals(numDocs, rsp.getResults().getNumFound());
        }

        // the test itself
        zkStateReader.updateClusterState();
        final ClusterState clusterState = zkStateReader.getClusterState();

        final HashSet<Integer> leaderIndices = new HashSet<Integer>();
        final HashSet<Integer> followerIndices = new HashSet<Integer>();
        {
          final HashMap<String,Boolean> shardLeaderMap = new HashMap<String,Boolean>();
          for (final Slice slice : clusterState.getSlices(collectionName)) {
            for (final Replica replica : slice.getReplicas()) {
              shardLeaderMap.put(replica.getNodeName().replace("_solr", "/solr"), Boolean.FALSE);
            }
            shardLeaderMap.put(slice.getLeader().getNodeName().replace("_solr", "/solr"), Boolean.TRUE);
          }
          for (int ii = 0; ii < jettys.size(); ++ii) {
            final URL jettyBaseUrl = jettys.get(ii).getBaseUrl();
            final String jettyBaseUrlString = jettyBaseUrl.toString().substring((jettyBaseUrl.getProtocol() + "://").length());
            final Boolean isLeader = shardLeaderMap.get(jettyBaseUrlString);
            if (Boolean.TRUE.equals(isLeader)) {
              leaderIndices.add(new Integer(ii));
            } else if (Boolean.FALSE.equals(isLeader)) {
              followerIndices.add(new Integer(ii));
            } // else neither leader nor follower i.e. node without a replica (for our collection)
          }
        }
        final List<Integer> leaderIndicesList = new ArrayList<Integer>(leaderIndices);
        final List<Integer> followerIndicesList = new ArrayList<Integer>(followerIndices);

        // first stop the followers (in no particular order)
        Collections.shuffle(followerIndicesList, random());
        for (Integer ii : followerIndicesList) {
          if (!leaderIndices.contains(ii)) {
            miniCluster.stopJettySolrRunner(jettys.get(ii.intValue()));
          }
        }

        // then stop the leaders (again in no particular order)
        Collections.shuffle(leaderIndicesList, random());
        for (Integer ii : leaderIndicesList) {
          miniCluster.stopJettySolrRunner(jettys.get(ii.intValue()));
        }

        // calculate restart order
        final List<Integer> restartIndicesList = new ArrayList<Integer>();
        Collections.shuffle(leaderIndicesList, random());
        restartIndicesList.addAll(leaderIndicesList);
        Collections.shuffle(followerIndicesList, random());
        restartIndicesList.addAll(followerIndicesList);
        if (random().nextBoolean()) Collections.shuffle(restartIndicesList, random());

        // and then restart jettys in that order
        for (Integer ii : restartIndicesList) {
          final JettySolrRunner jetty = jettys.get(ii.intValue());
          if (!jetty.isRunning()) {
            miniCluster.startJettySolrRunner(jetty);
            assertTrue(jetty.isRunning());
          }
        }
        AbstractDistribZkTestBase.waitForRecoveriesToFinish(collectionName, zkStateReader, true, true, 330);

        zkStateReader.updateClusterState();

        // re-query collection
        {
          final QueryResponse rsp = cloudSolrClient.query(query);
          assertEquals(numDocs, rsp.getResults().getNumFound());
        }

        // delete the collection we created earlier
        miniCluster.deleteCollection(collectionName);
        AbstractDistribZkTestBase.waitForCollectionToDisappear(collectionName, zkStateReader, true, true, 330);
      }
    }
    finally {
      miniCluster.shutdown();
    }
  }

  private class TestSegmentTerminateEarlyState {

    final Boolean smpfEnabled = random().nextBoolean() ? null : new Boolean(random().nextBoolean());

    final String keyField = "id";
    final String timestampField = "timestamp";
    final String oddField = "odd_l1"; // <dynamicField name="*_l1"  type="long"   indexed="true"  stored="true" multiValued="false"/>
    final String quadField = "quad_l1"; // <dynamicField name="*_l1"  type="long"   indexed="true"  stored="true" multiValued="false"/>

    final Set<Integer> minTimestampDocKeys = new HashSet<>();
    final Set<Integer> maxTimestampDocKeys = new HashSet<>();

    Integer minTimestampMM = null;
    Integer maxTimestampMM = null;

    int numDocs = 0;

    private void addDocuments(CloudSolrClient cloudSolrClient,
        int numCommits, int numDocsPerCommit, boolean optimize) throws Exception {
      for (int cc = 1; cc <= numCommits; ++cc) {
        for (int nn = 1; nn <= numDocsPerCommit; ++nn) {
          ++numDocs;
          final Integer docKey = new Integer(numDocs);
          SolrInputDocument doc = new SolrInputDocument();
          doc.setField(keyField, ""+docKey);
          final int MM = random().nextInt(60);
          if (minTimestampMM == null || MM <= minTimestampMM.intValue()) {
            if (minTimestampMM != null && MM < minTimestampMM.intValue()) {
              minTimestampDocKeys.clear();
            }
            minTimestampMM = new Integer(MM);
            minTimestampDocKeys.add(docKey);
          }
          if (maxTimestampMM == null || maxTimestampMM.intValue() <= MM) {
            if (maxTimestampMM != null && maxTimestampMM.intValue() < MM) {
              maxTimestampDocKeys.clear();
            }
            maxTimestampMM = new Integer(MM);
            maxTimestampDocKeys.add(docKey);
          }
          doc.setField(timestampField, "2016-01-01T00:"+MM+":00Z");
          doc.setField(oddField, ""+(numDocs % 2));
          doc.setField(quadField, ""+(numDocs % 4)+1);
          cloudSolrClient.add(doc);
        }
        cloudSolrClient.commit();
      }
      if (optimize) {
        cloudSolrClient.optimize();
      }
    }

    private void queryTimestampDescending(CloudSolrClient cloudSolrClient) throws Exception {
      assertFalse(maxTimestampDocKeys.isEmpty());
      assertTrue("numDocs="+numDocs+" is not even", (numDocs%2)==0);
      final Long oddFieldValue = new Long(maxTimestampDocKeys.iterator().next().intValue()%2);
      final SolrQuery query = new SolrQuery(oddField+":"+oddFieldValue);
      query.setSort(timestampField, SolrQuery.ORDER.desc);
      query.setFields(keyField, oddField, timestampField);
      query.setRows(1);
      // CommonParams.SEGMENT_TERMINATE_EARLY parameter intentionally absent
      final QueryResponse rsp = cloudSolrClient.query(query);
      // check correctness of the results count
      assertEquals("numFound", numDocs/2, rsp.getResults().getNumFound());
      // check correctness of the first result
      if (rsp.getResults().getNumFound() > 0) {
        final SolrDocument solrDocument0 = rsp.getResults().get(0);
        assertTrue(keyField+" of ("+solrDocument0+") is not in maxTimestampDocKeys("+maxTimestampDocKeys+")",
            maxTimestampDocKeys.contains(solrDocument0.getFieldValue(keyField)));
        assertEquals(oddField, oddFieldValue, solrDocument0.getFieldValue(oddField));
      }
      // check segmentTerminatedEarly flag
      assertNull("responseHeader.segmentTerminatedEarly present in "+rsp.getResponseHeader(),
          rsp.getResponseHeader().get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY));
    }

    private void queryTimestampDescendingSegmentTerminateEarlyYes(CloudSolrClient cloudSolrClient) throws Exception {
      assertFalse(maxTimestampDocKeys.isEmpty());
      assertTrue("numDocs="+numDocs+" is not even", (numDocs%2)==0);
      final Long oddFieldValue = new Long(maxTimestampDocKeys.iterator().next().intValue()%2);
      final SolrQuery query = new SolrQuery(oddField+":"+oddFieldValue);
      query.setSort(timestampField, SolrQuery.ORDER.desc);
      query.setFields(keyField, oddField, timestampField);
      final int rowsWanted = 1;
      query.setRows(rowsWanted);
      final Boolean shardsInfoWanted = (random().nextBoolean() ? null : new Boolean(random().nextBoolean()));
      if (shardsInfoWanted != null) {
        query.set(ShardParams.SHARDS_INFO, shardsInfoWanted.booleanValue());
      }
      query.set(CommonParams.SEGMENT_TERMINATE_EARLY, true);
      final QueryResponse rsp = cloudSolrClient.query(query);
      // check correctness of the results count
      if (!Boolean.FALSE.equals(smpfEnabled)) {
        assertTrue("numFound", rowsWanted <= rsp.getResults().getNumFound());
        assertTrue("numFound", rsp.getResults().getNumFound() <= numDocs/2);
      } else {
        assertEquals("numFound", numDocs/2, rsp.getResults().getNumFound());
      }
      // check correctness of the first result
      if (rsp.getResults().getNumFound() > 0) {
        final SolrDocument solrDocument0 = rsp.getResults().get(0);
        assertTrue(keyField+" of ("+solrDocument0+") is not in maxTimestampDocKeys("+maxTimestampDocKeys+")",
            maxTimestampDocKeys.contains(solrDocument0.getFieldValue(keyField)));
        assertEquals(oddField, oddFieldValue, rsp.getResults().get(0).getFieldValue(oddField));
      }
      // check segmentTerminatedEarly flag
      assertNotNull("responseHeader.segmentTerminatedEarly missing in "+rsp.getResponseHeader(),
          rsp.getResponseHeader().get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY));
      if (!Boolean.FALSE.equals(smpfEnabled)) {
        assertTrue("responseHeader.segmentTerminatedEarly missing/false in "+rsp.getResponseHeader(),
            Boolean.TRUE.equals(rsp.getResponseHeader().get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY)));
      } else {
        assertTrue("responseHeader.segmentTerminatedEarly missing/true in "+rsp.getResponseHeader(),
            Boolean.FALSE.equals(rsp.getResponseHeader().get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY)));
      }
      // check shards info
      final Object shardsInfo = rsp.getResponse().get(ShardParams.SHARDS_INFO);
      if (!Boolean.TRUE.equals(shardsInfoWanted)) {
        assertNull(ShardParams.SHARDS_INFO, shardsInfo);
      } else {
        assertNotNull(ShardParams.SHARDS_INFO, shardsInfo);
        int segmentTerminatedEarlyShardsCount = 0;
        for (Map.Entry<String, ?> si : (SimpleOrderedMap<?>)shardsInfo) {
          if (Boolean.TRUE.equals(((SimpleOrderedMap)si.getValue()).get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY))) {
            segmentTerminatedEarlyShardsCount += 1;
          }
        }
        // check segmentTerminatedEarly flag within shards info
        if (!Boolean.FALSE.equals(smpfEnabled)) {
          assertTrue(segmentTerminatedEarlyShardsCount+" shards reported "+SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY,
              (0<segmentTerminatedEarlyShardsCount));
        } else {
          assertEquals("shards reporting "+SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY,
              0, segmentTerminatedEarlyShardsCount);
        }
      }
    }

    private void queryTimestampDescendingSegmentTerminateEarlyNo(CloudSolrClient cloudSolrClient) throws Exception {
      assertFalse(maxTimestampDocKeys.isEmpty());
      assertTrue("numDocs="+numDocs+" is not even", (numDocs%2)==0);
      final Long oddFieldValue = new Long(maxTimestampDocKeys.iterator().next().intValue()%2);
      final SolrQuery query = new SolrQuery(oddField+":"+oddFieldValue);
      query.setSort(timestampField, SolrQuery.ORDER.desc);
      query.setFields(keyField, oddField, timestampField);
      query.setRows(1);
      final Boolean shardsInfoWanted = (random().nextBoolean() ? null : new Boolean(random().nextBoolean()));
      if (shardsInfoWanted != null) {
        query.set(ShardParams.SHARDS_INFO, shardsInfoWanted.booleanValue());
      }
      query.set(CommonParams.SEGMENT_TERMINATE_EARLY, false);
      final QueryResponse rsp = cloudSolrClient.query(query);
      // check correctness of the results count
      assertEquals("numFound", numDocs/2, rsp.getResults().getNumFound());
      // check correctness of the first result
      if (rsp.getResults().getNumFound() > 0) {
        final SolrDocument solrDocument0 = rsp.getResults().get(0);
        assertTrue(keyField+" of ("+solrDocument0+") is not in maxTimestampDocKeys("+maxTimestampDocKeys+")",
            maxTimestampDocKeys.contains(solrDocument0.getFieldValue(keyField)));
        assertEquals(oddField, oddFieldValue, rsp.getResults().get(0).getFieldValue(oddField));
      }
      // check segmentTerminatedEarly flag
      assertNull("responseHeader.segmentTerminatedEarly present in "+rsp.getResponseHeader(),
          rsp.getResponseHeader().get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY));
      assertFalse("responseHeader.segmentTerminatedEarly present/true in "+rsp.getResponseHeader(),
          Boolean.TRUE.equals(rsp.getResponseHeader().get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY)));
      // check shards info
      final Object shardsInfo = rsp.getResponse().get(ShardParams.SHARDS_INFO);
      if (!Boolean.TRUE.equals(shardsInfoWanted)) {
        assertNull(ShardParams.SHARDS_INFO, shardsInfo);
      } else {
        assertNotNull(ShardParams.SHARDS_INFO, shardsInfo);
        int segmentTerminatedEarlyShardsCount = 0;
        for (Map.Entry<String, ?> si : (SimpleOrderedMap<?>)shardsInfo) {
          if (Boolean.TRUE.equals(((SimpleOrderedMap)si.getValue()).get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY))) {
            segmentTerminatedEarlyShardsCount += 1;
          }
        }
        assertEquals("shards reporting "+SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY,
            0, segmentTerminatedEarlyShardsCount);
      }
    }

    private void queryTimestampDescendingSegmentTerminateEarlyYesGrouped(CloudSolrClient cloudSolrClient) throws Exception {
      assertFalse(maxTimestampDocKeys.isEmpty());
      assertTrue("numDocs="+numDocs+" is not even", (numDocs%2)==0);
      final Long oddFieldValue = new Long(maxTimestampDocKeys.iterator().next().intValue()%2);
      final SolrQuery query = new SolrQuery(oddField+":"+oddFieldValue);
      query.setSort(timestampField, SolrQuery.ORDER.desc);
      query.setFields(keyField, oddField, timestampField);
      query.setRows(1);
      query.set(CommonParams.SEGMENT_TERMINATE_EARLY, true);
      assertTrue("numDocs="+numDocs+" is not quad-able", (numDocs%4)==0);
      query.add("group.field", quadField);
      query.set("group", true);
      final QueryResponse rsp = cloudSolrClient.query(query);
      // check correctness of the results count
      assertEquals("matches", numDocs/2, rsp.getGroupResponse().getValues().get(0).getMatches());
      // check correctness of the first result
      if (rsp.getGroupResponse().getValues().get(0).getMatches() > 0) {
        final SolrDocument solrDocument = rsp.getGroupResponse().getValues().get(0).getValues().get(0).getResult().get(0);
        assertTrue(keyField+" of ("+solrDocument+") is not in maxTimestampDocKeys("+maxTimestampDocKeys+")",
            maxTimestampDocKeys.contains(solrDocument.getFieldValue(keyField)));
        assertEquals(oddField, oddFieldValue, solrDocument.getFieldValue(oddField));
      }
      // check segmentTerminatedEarly flag
      // at present segmentTerminateEarly cannot be used with grouped queries
      assertFalse("responseHeader.segmentTerminatedEarly present/true in "+rsp.getResponseHeader(),
          Boolean.TRUE.equals(rsp.getResponseHeader().get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY)));
    }

    private void queryTimestampAscendingSegmentTerminateEarlyYes(CloudSolrClient cloudSolrClient) throws Exception {
      assertFalse(minTimestampDocKeys.isEmpty());
      assertTrue("numDocs="+numDocs+" is not even", (numDocs%2)==0);
      final Long oddFieldValue = new Long(minTimestampDocKeys.iterator().next().intValue()%2);
      final SolrQuery query = new SolrQuery(oddField+":"+oddFieldValue);
      query.setSort(timestampField, SolrQuery.ORDER.asc); // a sort order that is _not_ compatible with the merge sort order
      query.setFields(keyField, oddField, timestampField);
      query.setRows(1);
      query.set(CommonParams.SEGMENT_TERMINATE_EARLY, true);
      final QueryResponse rsp = cloudSolrClient.query(query);
      // check correctness of the results count
      assertEquals("numFound", numDocs/2, rsp.getResults().getNumFound());
      // check correctness of the first result
      if (rsp.getResults().getNumFound() > 0) {
        final SolrDocument solrDocument0 = rsp.getResults().get(0);
        assertTrue(keyField+" of ("+solrDocument0+") is not in minTimestampDocKeys("+minTimestampDocKeys+")",
            minTimestampDocKeys.contains(solrDocument0.getFieldValue(keyField)));
        assertEquals(oddField, oddFieldValue, solrDocument0.getFieldValue(oddField));
      }
      // check segmentTerminatedEarly flag
      assertNotNull("responseHeader.segmentTerminatedEarly missing in "+rsp.getResponseHeader(),
          rsp.getResponseHeader().get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY));
      // segmentTerminateEarly cannot be used with incompatible sort orders
      assertTrue("responseHeader.segmentTerminatedEarly missing/true in "+rsp.getResponseHeader(),
          Boolean.FALSE.equals(rsp.getResponseHeader().get(SolrQueryResponse.RESPONSE_HEADER_SEGMENT_TERMINATED_EARLY_KEY)));
    }
  }

  @Test
  public void testSegmentTerminateEarly() throws Exception {

    final String collectionName = "testSegmentTerminateEarlyCollection";
    final String solr_sortingMergePolicyFactory_enable = "solr.sortingMergePolicyFactory.enable";

    final TestSegmentTerminateEarlyState tstes = new TestSegmentTerminateEarlyState();
    if (tstes.smpfEnabled != null) {
      System.setProperty(solr_sortingMergePolicyFactory_enable, tstes.smpfEnabled.toString());
    }

    File solrXml = new File(SolrTestCaseJ4.TEST_HOME(), "solr-no-core.xml");
    Builder jettyConfig = JettyConfig.builder();
    jettyConfig.waitForLoadingCoresToFinish(null);
    final MiniSolrCloudCluster miniCluster = createMiniSolrCloudCluster();
    final CloudSolrClient cloudSolrClient = miniCluster.getSolrClient();
    cloudSolrClient.setDefaultCollection(collectionName);

    try {
      // create collection
      {
        final String asyncId = (random().nextBoolean() ? null : "asyncId("+collectionName+".create)="+random().nextInt());
        final Map<String, String> collectionProperties = new HashMap<>();
        collectionProperties.put(CoreDescriptor.CORE_CONFIG, "solrconfig-sortingmergepolicyfactory.xml");
        createCollection(miniCluster, collectionName, null, asyncId, Boolean.TRUE, collectionProperties);
        if (asyncId != null) {
          final RequestStatusState state = AbstractFullDistribZkTestBase.getRequestStateAfterCompletion(asyncId, 330, cloudSolrClient);
          assertSame("did not see async createCollection completion", RequestStatusState.COMPLETED, state);
        }
      }

      try (SolrZkClient zkClient = new SolrZkClient
          (miniCluster.getZkServer().getZkAddress(), AbstractZkTestCase.TIMEOUT, 45000, null);
          ZkStateReader zkStateReader = new ZkStateReader(zkClient)) {
        AbstractDistribZkTestBase.waitForRecoveriesToFinish(collectionName, zkStateReader, true, true, 330);

        // add some documents, then optimize to get merged-sorted segments
        tstes.addDocuments(cloudSolrClient, 10, 10, true);

        // CommonParams.SEGMENT_TERMINATE_EARLY parameter intentionally absent
        tstes.queryTimestampDescending(cloudSolrClient);

        // add a few more documents, but don't optimize to have some not-merge-sorted segments
        tstes.addDocuments(cloudSolrClient, 2, 10, false);

        // CommonParams.SEGMENT_TERMINATE_EARLY parameter now present
        tstes.queryTimestampDescendingSegmentTerminateEarlyYes(cloudSolrClient);
        tstes.queryTimestampDescendingSegmentTerminateEarlyNo(cloudSolrClient);

        // CommonParams.SEGMENT_TERMINATE_EARLY parameter present but it won't be used
        tstes.queryTimestampDescendingSegmentTerminateEarlyYesGrouped(cloudSolrClient);
        tstes.queryTimestampAscendingSegmentTerminateEarlyYes(cloudSolrClient); // uses a sort order that is _not_ compatible with the merge sort order

        // delete the collection we created earlier
        miniCluster.deleteCollection(collectionName);
        AbstractDistribZkTestBase.waitForCollectionToDisappear(collectionName, zkStateReader, true, true, 330);
      }
    }
    finally {
      miniCluster.shutdown();
    }
    System.clearProperty(solr_sortingMergePolicyFactory_enable);
  }

}
