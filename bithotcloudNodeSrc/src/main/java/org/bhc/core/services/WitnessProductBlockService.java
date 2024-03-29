package org.bhc.core.services;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.bhc.common.utils.ByteArray;
import org.bhc.core.capsule.BlockCapsule;

@Slf4j(topic = "witness")
@Service
public class WitnessProductBlockService {

  private Cache<Long, BlockCapsule> historyBlockCapsuleCache = CacheBuilder.newBuilder()
      .initialCapacity(200).maximumSize(200).build();

  //通过收到的Block检测出是个虚假区块，进而把生产该区块的代表记录为骗子代表。
  private Map<String, CheatWitnessInfo> cheatWitnessInfoMap = new HashMap<>();

  //骗子代表类
  public static class CheatWitnessInfo {

    private AtomicInteger times = new AtomicInteger(0);
    private long latestBlockNum;
    private Set<BlockCapsule> blockCapsuleSet = new HashSet<>();
    private long time;

    public CheatWitnessInfo increment() {
      times.incrementAndGet();
      return this;
    }

    public AtomicInteger getTimes() {
      return times;
    }

    public CheatWitnessInfo setTimes(AtomicInteger times) {
      this.times = times;
      return this;
    }

    public long getLatestBlockNum() {
      return latestBlockNum;
    }

    public CheatWitnessInfo setLatestBlockNum(long latestBlockNum) {
      this.latestBlockNum = latestBlockNum;
      return this;
    }

    public Set<BlockCapsule> getBlockCapsuleSet() {
      return new HashSet<>(blockCapsuleSet);
    }

    public CheatWitnessInfo clear() {
      blockCapsuleSet.clear();
      return this;
    }

    public CheatWitnessInfo add(BlockCapsule blockCapsule) {
      blockCapsuleSet.add(blockCapsule);
      return this;
    }

    public CheatWitnessInfo setBlockCapsuleSet(Set<BlockCapsule> blockCapsuleSet) {
      this.blockCapsuleSet = new HashSet<>(blockCapsuleSet);
      return this;
    }

    public long getTime() {
      return time;
    }

    public CheatWitnessInfo setTime(long time) {
      this.time = time;
      return this;
    }

    @Override
    public String toString() {
      return "{" +
          "times=" + times.get() +
          ", time=" + time +
          ", latestBlockNum=" + latestBlockNum +
          ", blockCapsuleSet=" + blockCapsuleSet +
          '}';
    }
  }

  /* 如果收到一个block，在cache中存在相同编号的区块， 产生这个区块的代表相同，但是区块id不同，这就很诡异了。
   * 说明一个代表生产了相同编号的区块2次， 可能是故意的欺骗者。
   * */
  public void validWitnessProductTwoBlock(BlockCapsule block) {
    try {
      BlockCapsule blockCapsule = historyBlockCapsuleCache.getIfPresent(block.getNum());
      if (blockCapsule != null 
    	  && Arrays.equals(blockCapsule.getWitnessAddress().toByteArray(),
                           block.getWitnessAddress().toByteArray()) 
    	  && !Arrays.equals(block.getBlockId().getBytes(),
                           blockCapsule.getBlockId().getBytes())    ) {
        String key = ByteArray.toHexString(block.getWitnessAddress().toByteArray());
        if (!cheatWitnessInfoMap.containsKey(key)) {
          CheatWitnessInfo cheatWitnessInfo = new CheatWitnessInfo();
          cheatWitnessInfoMap.put(key, cheatWitnessInfo);
        }
        cheatWitnessInfoMap.get(key).clear().setTime(System.currentTimeMillis())
            .setLatestBlockNum(block.getNum()).add(block).add(blockCapsule).increment();
      } else {
        historyBlockCapsuleCache.put(block.getNum(), block);
      }
    } catch (Exception e) {
      logger.error("valid witness same time product two block fail! blockNum: {}, blockHash: {}",
          block.getNum(), block.getBlockId().toString(), e);
    }
  }

  public Map<String, CheatWitnessInfo> queryCheatWitnessInfo() {
    return cheatWitnessInfoMap;
  }
}
