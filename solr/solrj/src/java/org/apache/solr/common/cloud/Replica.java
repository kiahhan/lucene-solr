package org.apache.solr.common.cloud;

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

import org.noggit.JSONUtil;

import java.util.Map;

import static org.apache.solr.common.cloud.ZkStateReader.BASE_URL_PROP;
import static org.apache.solr.common.cloud.ZkStateReader.CORE_NAME_PROP;

public class Replica extends ZkNodeProps {
  private final String name;
  private final String nodeName;

  public Replica(String name, Map<String,Object> propMap) {
    super(propMap);
    this.name = name;
    nodeName = (String)propMap.get(ZkStateReader.NODE_NAME_PROP);
  }

  public String getName() {
    return name;
  }
  public String getCoreUrl() {
    return ZkCoreNodeProps.getCoreUrl(getStr(BASE_URL_PROP), getStr(CORE_NAME_PROP));
  }

  /** The name of the node this replica resides on */
  public String getNodeName() {
    return nodeName;
  }

  @Override
  public String toString() {
    return name + ':' + JSONUtil.toJSON(propMap, -1); // small enough, keep it on one line (i.e. no indent)
  }
}
