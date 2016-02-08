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

import org.apache.lucene.index.LogDocMergePolicy;
import org.apache.lucene.index.MergePolicy;
import org.apache.solr.core.SolrResourceLoader;

/**
 * A {@link MergePolicyFactory} for {@link LogDocMergePolicy} objects.
 */
public class LogDocMergePolicyFactory extends SimpleMergePolicyFactory {

  public LogDocMergePolicyFactory(SolrResourceLoader resourceLoader, MergePolicyFactoryArgs args) {
    super(resourceLoader, args);
  }

  @Override
  protected MergePolicy getMergePolicyInstance() {
    return new LogDocMergePolicy();
  }
  
}
