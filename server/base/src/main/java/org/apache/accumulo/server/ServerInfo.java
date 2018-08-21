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
package org.apache.accumulo.server;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.util.Properties;
import java.util.UUID;

import org.apache.accumulo.core.Constants;
import org.apache.accumulo.core.client.ClientInfo;
import org.apache.accumulo.core.client.impl.ClientConfConverter;
import org.apache.accumulo.core.client.impl.Credentials;
import org.apache.accumulo.core.client.impl.InstanceOperationsImpl;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.conf.ClientProperty;
import org.apache.accumulo.core.conf.Property;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.core.zookeeper.ZooUtil;
import org.apache.accumulo.fate.zookeeper.ZooCache;
import org.apache.accumulo.fate.zookeeper.ZooCacheFactory;
import org.apache.accumulo.server.fs.VolumeManager;
import org.apache.accumulo.server.fs.VolumeManagerImpl;
import org.apache.accumulo.server.security.SystemCredentials;
import org.apache.hadoop.fs.Path;

public class ServerInfo implements ClientInfo {

  private SiteConfiguration siteConfig;
  private String instanceID;
  private String instanceName;
  private String zooKeepers;
  private int zooKeepersSessionTimeOut;
  private String zooKeeperRoot;
  private VolumeManager volumeManager;
  private ZooCache zooCache;

  ServerInfo(SiteConfiguration siteConfig, String instanceName, String zooKeepers,
      int zooKeepersSessionTimeOut) {
    this.siteConfig = siteConfig;
    this.instanceName = instanceName;
    this.zooKeepers = zooKeepers;
    this.zooKeepersSessionTimeOut = zooKeepersSessionTimeOut;
    zooCache = new ZooCacheFactory().getZooCache(zooKeepers, zooKeepersSessionTimeOut);
    String instanceNamePath = Constants.ZROOT + Constants.ZINSTANCES + "/" + instanceName;
    byte[] iidb = zooCache.get(instanceNamePath);
    if (iidb == null) {
      throw new RuntimeException("Instance name " + instanceName + " does not exist in zookeeper. "
          + "Run \"accumulo org.apache.accumulo.server.util.ListInstances\" to see a list.");
    }
    instanceID = new String(iidb, UTF_8);
    if (zooCache.get(Constants.ZROOT + "/" + instanceID) == null) {
      if (instanceName == null) {
        throw new RuntimeException("Instance id " + instanceID + " does not exist in zookeeper");
      }
      throw new RuntimeException("Instance id " + instanceID + " pointed to by the name "
          + instanceName + " does not exist in zookeeper");
    }
    zooKeeperRoot = ZooUtil.getRoot(instanceID);
  }

  ServerInfo(SiteConfiguration config) {
    siteConfig = config;
    try {
      volumeManager = VolumeManagerImpl.get();
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    Path instanceIdPath = Accumulo.getAccumuloInstanceIdPath(volumeManager);
    instanceID = ZooUtil.getInstanceIDFromHdfs(instanceIdPath, config);
    zooKeeperRoot = ZooUtil.getRoot(instanceID);
    zooKeepers = config.get(Property.INSTANCE_ZK_HOST);
    zooKeepersSessionTimeOut = (int) config.getTimeInMillis(Property.INSTANCE_ZK_TIMEOUT);
    zooCache = new ZooCacheFactory().getZooCache(zooKeepers, zooKeepersSessionTimeOut);
    instanceName = InstanceOperationsImpl.lookupInstanceName(zooCache, UUID.fromString(instanceID));
  }

  public SiteConfiguration getSiteConfiguration() {
    return siteConfig;
  }

  public VolumeManager getVolumeManager() {
    return volumeManager;
  }

  public String getInstanceID() {
    return instanceID;
  }

  public String getZooKeeperRoot() {
    return zooKeeperRoot;
  }

  public String getZooKeepers() {
    return zooKeepers;
  }

  public int getZooKeepersSessionTimeOut() {
    return zooKeepersSessionTimeOut;
  }

  @Override
  public String getPrincipal() {
    return getCredentials().getPrincipal();
  }

  @Override
  public AuthenticationToken getAuthenticationToken() {
    return getCredentials().getToken();
  }

  @Override
  public boolean saslEnabled() {
    return getSiteConfiguration().getBoolean(Property.INSTANCE_RPC_SASL_ENABLED);
  }

  @Override
  public Properties getProperties() {
    Properties properties = ClientConfConverter.toProperties(getSiteConfiguration());
    properties.setProperty(ClientProperty.INSTANCE_ZOOKEEPERS.getKey(), getZooKeepers());
    properties.setProperty(ClientProperty.INSTANCE_ZOOKEEPERS_TIMEOUT.getKey(),
        Integer.toString(getZooKeepersSessionTimeOut()));
    properties.setProperty(ClientProperty.INSTANCE_NAME.getKey(), getInstanceName());
    ClientProperty.setAuthenticationToken(properties, getAuthenticationToken());
    properties.setProperty(ClientProperty.AUTH_PRINCIPAL.getKey(), getPrincipal());
    return properties;
  }

  public String getInstanceName() {
    return instanceName;
  }

  public Credentials getCredentials() {
    return SystemCredentials.get(getInstanceID(), getSiteConfiguration());
  }
}
