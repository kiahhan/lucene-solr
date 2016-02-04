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
package org.apache.solr.index;

import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.TieredMergePolicy;
import org.apache.solr.SolrTestCaseJ4;
import org.apache.solr.core.SolrResourceLoader;

/** Unit tests for {@link WrapperMergePolicyFactory}. */
public class WrapperMergePolicyFactoryTest extends SolrTestCaseJ4 {

  private final SolrResourceLoader resourceLoader = new SolrResourceLoader();

  public void testReturnsDefaultMergePolicyIfNoneSpecified() {
    final MergePolicyFactoryArgs args = new MergePolicyFactoryArgs();
    MergePolicyFactory mpf = new DefaultingWrapperMergePolicyFactory(resourceLoader, args);
    assertSame(mpf.getMergePolicy(), NoMergePolicy.INSTANCE);
  }

  public void testFailsIfNoClassSpecifiedForWrappedPolicy() {
    final MergePolicyFactoryArgs args = new MergePolicyFactoryArgs();
    args.put(WrapperMergePolicyFactory.WRAPPED_PREFIX, "foo");
    try {
      new DefaultingWrapperMergePolicyFactory(resourceLoader, args).getMergePolicy();
      fail("Should have failed when no 'class' specified for wrapped merge policy");
    } catch (final IllegalArgumentException e) {
      // Good!
    }
  }

  public void testProperlyInitializesWrappedMergePolicy() {
    final TieredMergePolicy defaultTMP = new TieredMergePolicy();
    final int testMaxMergeAtOnce = defaultTMP.getMaxMergeAtOnce() * 2;
    final double testMaxMergedSegmentMB = defaultTMP.getMaxMergedSegmentMB() * 10;

    final MergePolicyFactoryArgs args = new MergePolicyFactoryArgs();
    args.put(WrapperMergePolicyFactory.WRAPPED_PREFIX, "test");
    args.put("test.class", TieredMergePolicyFactory.class.getName());
    args.put("test.maxMergeAtOnce", testMaxMergeAtOnce);
    args.put("test.maxMergedSegmentMB", testMaxMergedSegmentMB);
    MergePolicyFactory mpf = new DefaultingWrapperMergePolicyFactory(resourceLoader, args) {
      @Override
      protected MergePolicy getDefaultWrappedMergePolicy() {
        throw new IllegalStateException("Should not have reached here!");
      }
    };
    final MergePolicy mp = mpf.getMergePolicy();
    assertSame(mp.getClass(), TieredMergePolicy.class);
    final TieredMergePolicy tmp = (TieredMergePolicy)mp;
    assertEquals("maxMergeAtOnce", testMaxMergeAtOnce, tmp.getMaxMergeAtOnce());
    assertEquals("maxMergedSegmentMB", testMaxMergedSegmentMB, tmp.getMaxMergedSegmentMB(), 0.0d);
  }
  
  private static class DefaultingWrapperMergePolicyFactory extends WrapperMergePolicyFactory {

    DefaultingWrapperMergePolicyFactory(SolrResourceLoader resourceLoader, MergePolicyFactoryArgs wrapperArgs) {
      super(resourceLoader, wrapperArgs);
      if (!args.keys().isEmpty()) {
        throw new IllegalArgumentException("All arguments should have been claimed by the wrapped policy but some ("+args.keys()+") remain.");
      }
    }

    @Override
    protected MergePolicy getDefaultWrappedMergePolicy() {
      return NoMergePolicy.INSTANCE;
    }

    @Override
    public MergePolicy getMergePolicy() {
      return getWrappedMergePolicy();
    }

  }

}
