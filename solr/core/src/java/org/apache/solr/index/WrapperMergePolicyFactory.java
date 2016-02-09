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

import java.util.Iterator;

import org.apache.lucene.index.MergePolicy;
import org.apache.solr.core.SolrResourceLoader;
import org.apache.solr.schema.IndexSchema;

/**
 * A {@link MergePolicyFactory} for wrapping additional {@link MergePolicyFactory factories}.
 */
public abstract class WrapperMergePolicyFactory extends MergePolicyFactory {

  private static final String CLASS = "class";

  protected static final String[] NO_SUB_PACKAGES = new String[0];

  static final String WRAPPED_PREFIX = "wrapped.prefix"; // not private so that test(s) can use it

  private final MergePolicyFactoryArgs wrappedMergePolicyArgs;

  protected WrapperMergePolicyFactory(SolrResourceLoader resourceLoader, MergePolicyFactoryArgs args, IndexSchema schema) {
    super(resourceLoader, args, schema);
    wrappedMergePolicyArgs = filterWrappedMergePolicyFactoryArgs();
  }

  /**
   * Returns the default wrapped {@link MergePolicy}. This is called if the factory settings do not explicitly specify
   * the wrapped policy.
   */
  protected MergePolicy getDefaultWrappedMergePolicy() {
    final MergePolicyFactory mpf = new DefaultMergePolicyFactory();
    return mpf.getMergePolicy();
  }

  /** Returns an instance of the wrapped {@link MergePolicy} after it has been configured with all set parameters. */
  protected final MergePolicy getWrappedMergePolicy() {
    if (wrappedMergePolicyArgs == null) {
      return getDefaultWrappedMergePolicy();
    }

    final String className = (String) wrappedMergePolicyArgs.remove(CLASS);
    if (className == null) {
      throw new IllegalArgumentException("Class name not defined for wrapped MergePolicyFactory!");
    }

    final MergePolicyFactory mpf = resourceLoader.newInstance(
        className,
        MergePolicyFactory.class,
        NO_SUB_PACKAGES,
        new Class[] {SolrResourceLoader.class, MergePolicyFactoryArgs.class, IndexSchema.class},
        new Object[] {resourceLoader, wrappedMergePolicyArgs, schema});
    return mpf.getMergePolicy();
  }

  /**
   * Returns a {@link MergePolicyFactoryArgs} for the wrapped {@link MergePolicyFactory}. This method also removes all
   * args from this instance's args.
   */
  private MergePolicyFactoryArgs filterWrappedMergePolicyFactoryArgs() {
    final String wrappedPolicyPrefix = (String) args.remove(WRAPPED_PREFIX);
    if (wrappedPolicyPrefix == null) {
      return null;
    }

    final String baseArgsPrefix = wrappedPolicyPrefix + '.';
    final int baseArgsPrefixLength = baseArgsPrefix.length();
    final MergePolicyFactoryArgs wrappedArgs = new MergePolicyFactoryArgs();
    for (final Iterator<String> iter = args.keys().iterator(); iter.hasNext();) {
      final String key = iter.next();
      if (key.startsWith(baseArgsPrefix)) {
        wrappedArgs.add(key.substring(baseArgsPrefixLength), args.get(key));
        iter.remove();
      }
    }
    return wrappedArgs;
  }

}
