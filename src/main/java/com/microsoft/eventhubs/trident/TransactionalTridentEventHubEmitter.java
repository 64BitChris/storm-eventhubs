/*******************************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package com.microsoft.eventhubs.trident;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.microsoft.eventhubs.spout.EventData;
import com.microsoft.eventhubs.spout.EventHubReceiverImpl;
import com.microsoft.eventhubs.spout.EventHubSpoutConfig;
import com.microsoft.eventhubs.spout.IEventHubReceiver;
import com.microsoft.eventhubs.spout.IEventHubReceiverFactory;
import com.microsoft.eventhubs.client.Constants;

import storm.trident.operation.TridentCollector;
import storm.trident.spout.IOpaquePartitionedTridentSpout;
import storm.trident.spout.IPartitionedTridentSpout;
import storm.trident.topology.TransactionAttempt;


public class TransactionalTridentEventHubEmitter
    implements IPartitionedTridentSpout.Emitter<Partitions, Partition, Map> {
  private static final Logger logger = LoggerFactory.getLogger(TransactionalTridentEventHubEmitter.class);
  private final int batchSize; 
  private final EventHubSpoutConfig spoutConfig;
  private Map<String, ITridentPartitionManager> pmMap;
  private ITridentPartitionManagerFactory pmFactory;
  private IEventHubReceiverFactory recvFactory;

  public TransactionalTridentEventHubEmitter(EventHubSpoutConfig spoutConfig) {
    //use batch size that matches the default credit size
    this(spoutConfig, spoutConfig.getReceiverCredits(), null, null);
  }
      
  public TransactionalTridentEventHubEmitter(final EventHubSpoutConfig spoutConfig,
      int batchSize,
      ITridentPartitionManagerFactory pmFactory,
      IEventHubReceiverFactory recvFactory) {
    this.spoutConfig = spoutConfig;
    this.batchSize = batchSize;
    this.pmFactory = pmFactory;
    this.recvFactory = recvFactory;
    pmMap = new HashMap<String, ITridentPartitionManager>();
    if(this.pmFactory == null) {
      this.pmFactory = new ITridentPartitionManagerFactory() {
        @Override
        public ITridentPartitionManager create(IEventHubReceiver receiver) {
          return new TridentPartitionManager(spoutConfig, receiver);
        }
      };
    }
    if(this.recvFactory == null) {
      this.recvFactory = new IEventHubReceiverFactory() {
        @Override
        public IEventHubReceiver create(EventHubSpoutConfig config,
            String partitionId) {
          return new EventHubReceiverImpl(config, partitionId);
        }
      };
    }
  }
  
  @Override
  public void close() {
    for(ITridentPartitionManager pm: pmMap.values()) {
      pm.close();
    }
  }
  
  /**
   * Check if partition manager for a given partiton is created
   * if not, create it.
   * @param partition
   */
  private ITridentPartitionManager getOrCreatePartitionManager(Partition partition) {
    ITridentPartitionManager pm;
    if(!pmMap.containsKey(partition.getId())) {
      IEventHubReceiver receiver = recvFactory.create(spoutConfig, partition.getId());
      pm = pmFactory.create(receiver);
      pmMap.put(partition.getId(), pm);
    }
    else {
      pm = pmMap.get(partition.getId());
    }
    return pm;
  }

  @Override
  public void emitPartitionBatch(TransactionAttempt attempt,
      TridentCollector collector, Partition partition, Map meta) {
    String offset = (String)meta.get("offset");
    int count = Integer.parseInt((String)meta.get("count"));
    logger.info("re-emit for partition " + partition.getId() + ", offset=" + offset + ", count=" + count);
    ITridentPartitionManager pm = getOrCreatePartitionManager(partition);
    List<EventData> listEvents = pm.receiveBatch(offset, count);
    if(listEvents.size() != count) {
      logger.error("failed to refetch eventhub messages, new count=" + listEvents.size());
      return;
    }

    for(EventData ed: listEvents) {
      List<Object> tuples = 
          spoutConfig.getEventDataScheme().deserialize(ed.getMessage());
      collector.emit(tuples);
    }
  }

  @Override
  public Map emitPartitionBatchNew(TransactionAttempt attempt,
      TridentCollector collector, Partition partition, Map meta) {
    ITridentPartitionManager pm = getOrCreatePartitionManager(partition);
    String offset = Constants.DefaultStartingOffset;
    if(meta != null && meta.containsKey("nextOffset")) {
      offset = (String)meta.get("nextOffset");
    }
    //logger.info("emit for partition " + partition.getId() + ", offset=" + offset);
    String nextOffset = offset;

    List<EventData> listEvents = pm.receiveBatch(offset, batchSize);

    for(EventData ed: listEvents) {
      //update nextOffset;
      nextOffset = ed.getMessageId().getOffset();
      List<Object> tuples = 
          spoutConfig.getEventDataScheme().deserialize(ed.getMessage());
      collector.emit(tuples);
    }
    //logger.info("emitted new batches: " + listEvents.size());
    
    Map newMeta = new HashMap();
    newMeta.put("offset", offset);
    newMeta.put("nextOffset", nextOffset);
    newMeta.put("count", ""+listEvents.size());
    return newMeta;
  }

  @Override
  public List<Partition> getOrderedPartitions(Partitions partitions) {
    return partitions.getPartitions();
  }

  @Override
  public void refreshPartitions(List<Partition> partitionList) {
    //partition info does not change in EventHub
    return;
  }

}
