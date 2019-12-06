package org.bhc.core.witness;

import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.joda.time.DateTime;
import org.bhc.common.utils.ByteArray;
import org.bhc.common.utils.StringUtil;
import org.bhc.common.utils.Time;
import org.bhc.core.capsule.AccountCapsule;
import org.bhc.core.capsule.BlockCapsule;
import org.bhc.core.capsule.VotesCapsule;
import org.bhc.core.capsule.WitnessCapsule;
import org.bhc.core.config.Parameter.ChainConstant;
import org.bhc.core.config.args.Args;
import org.bhc.core.db.AccountStore;
import org.bhc.core.db.Manager;
import org.bhc.core.db.VotesStore;
import org.bhc.core.db.WitnessStore;
import org.bhc.core.exception.HeaderNotFound;

@Slf4j(topic = "witness")
public class WitnessController {

  @Setter
  @Getter
  private Manager manager;

  private AtomicBoolean generatingBlock = new AtomicBoolean(false);

  public static WitnessController createInstance(Manager manager) {
    WitnessController instance = new WitnessController();
    instance.setManager(manager);
    return instance;
  }


  public void initWits() {
    // getWitnesses().clear();
    List<ByteString> witnessAddresses = new ArrayList<>();
    manager.getWitnessStore().getAllWitnesses().forEach(witnessCapsule -> {
      if (witnessCapsule.getIsJobs()) {  //筛选出来已工作的代表
        witnessAddresses.add(witnessCapsule.getAddress());
      }
    });
    sortWitness(witnessAddresses);
    setActiveWitnesses(witnessAddresses);
    witnessAddresses.forEach(address -> {
      logger.info("initWits shuffled addresses:" + ByteArray.toHexString(address.toByteArray()));
    });
    setCurrentShuffledWitnesses(witnessAddresses);
  }

  public WitnessCapsule getWitnesseByAddress(ByteString address) {
    return this.manager.getWitnessStore().get(address.toByteArray());
  }

  public List<ByteString> getActiveWitnesses() {
    return this.manager.getWitnessScheduleStore().getActiveWitnesses();
  }

  public void setActiveWitnesses(List<ByteString> addresses) {
    this.manager.getWitnessScheduleStore().saveActiveWitnesses(addresses);
  }

  public void addWitness(ByteString address) {
    List<ByteString> l = getActiveWitnesses();
    l.add(address);
    setActiveWitnesses(l);
  }

  public List<ByteString> getCurrentShuffledWitnesses() {
    return this.manager.getWitnessScheduleStore().getCurrentShuffledWitnesses();
  }

  public void setCurrentShuffledWitnesses(List<ByteString> addresses) {
    this.manager.getWitnessScheduleStore().saveCurrentShuffledWitnesses(addresses);
  }

  /**
   * get slot at time. 表示当前时间点when与区块时间戳的差距， 差了多少个区块数
   */
  public long getSlotAtTime(long when) {
    long firstSlotTime = getSlotTime(1);  //获取下一个区块的时间点
    logger.info("firstSlotTime={}",new DateTime(firstSlotTime));
    if (when < firstSlotTime) {   //when小于下一个区块时间点，说明指定的时间点when晚了。
      return 0;
    }
    logger.info("nextFirstSlotTime:[{}],when[{}]", new DateTime(firstSlotTime), new DateTime(when));
    return (when - firstSlotTime) / ChainConstant.BLOCK_PRODUCED_INTERVAL + 1;
  }

  public BlockCapsule getGenesisBlock() {
    return manager.getGenesisBlock();
  }

  public BlockCapsule getHead() throws HeaderNotFound {
    return manager.getHead();
  }

  public boolean lastHeadBlockIsMaintenance() {
    return manager.lastHeadBlockIsMaintenance();
  }

  /**
   * get absolute Slot At Time
   */
  public long getAbSlotAtTime(long when) {
    return (when - getGenesisBlock().getTimeStamp()) / ChainConstant.BLOCK_PRODUCED_INTERVAL;
  }

  /**
   * get slot time.  获取下一个区块的时间点
   */
  public long getSlotTime(long slotNum) {
    if (slotNum == 0) {
      return Time.getCurrentMillis();
    }
    long interval = ChainConstant.BLOCK_PRODUCED_INTERVAL;
    //System.out.println("   getSlotTime:  0001   ");
    //Number=0的只有创世区块，因此这是从创世区块开始计算下一个区块时间间隔。
    if (manager.getDynamicPropertiesStore().getLatestBlockHeaderNumber() == 0) {
    	logger.info("Genesis.TimeStamp={}",getGenesisBlock().getTimeStamp());
      return getGenesisBlock().getTimeStamp() + slotNum * interval;
    }
    //System.out.println("   getSlotTime:  0002   ");
    //维护情况下，时间点跳过2个槽。 通过trace追踪发现，生成block1之后处于维护状态，导致跳过2个slot，
    //时间上就推迟了6秒才创建block2。之后不是维护状态。
    if (lastHeadBlockIsMaintenance()) {
    	//System.out.println("   getSlotTime:  0002 000    slotNum="+slotNum);
      slotNum += manager.getSkipSlotInMaintenance();
        //System.out.println("   getSlotTime:  0002 111    slotNum="+slotNum);
    }
    System.out.println("   getSlotTime:  0003   ");
    //这是消除累积时间误差。 %模运算就是累积误差，是多余的时间，必须减去才能消除。
    long headSlotTime = manager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();
    headSlotTime = headSlotTime - ((headSlotTime - getGenesisBlock().getTimeStamp()) % interval);
    
    long  ret = headSlotTime + interval * slotNum;
    System.out.println("   getSlotTime:  0004   ret="+ret);
    return ret;
  }

  /**
   * validate witness schedule.
   */
  public boolean validateWitnessSchedule(BlockCapsule block) {

    ByteString witnessAddress = block.getInstance().getBlockHeader().getRawData()
        .getWitnessAddress();
    long timeStamp = block.getTimeStamp();
    return validateWitnessSchedule(witnessAddress, timeStamp);
  }

  //验证生产时间、证人地址。  感觉毫无意义，因为之前的生产流程就已经检验过一遍了。
  public boolean validateWitnessSchedule(ByteString witnessAddress, long timeStamp) {

    //to deal with other condition later
    if (manager.getDynamicPropertiesStore().getLatestBlockHeaderNumber() == 0) {
      return true;
    }
    long blockAbSlot = getAbSlotAtTime(timeStamp);
    long headBlockAbSlot = getAbSlotAtTime(
        manager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp());
    if (blockAbSlot <= headBlockAbSlot) {
      logger.warn("blockAbSlot is equals with headBlockAbSlot[" + blockAbSlot + "]");
      return false;
    }

    long slot = getSlotAtTime(timeStamp);
    final ByteString scheduledWitness = getScheduledWitness(slot);
    if (!scheduledWitness.equals(witnessAddress)) {
      logger.warn(
          "Witness is out of order, scheduledWitness[{}],blockWitnessAddress[{}],blockTimeStamp[{}],slot[{}]",
          ByteArray.toHexString(scheduledWitness.toByteArray()),
          ByteArray.toHexString(witnessAddress.toByteArray()), new DateTime(timeStamp),
          slot);
      return false;
    }

    logger.debug("Validate witnessSchedule successfully,scheduledWitness:{}",
        ByteArray.toHexString(witnessAddress.toByteArray()));
    return true;
  }

  public boolean activeWitnessesContain(final Set<ByteString> localWitnesses) {
    List<ByteString> activeWitnesses = this.getActiveWitnesses();
    for (ByteString witnessAddress : localWitnesses) {
      if (activeWitnesses.contains(witnessAddress)) {
        return true;
      }
    }
    return false;
  }

  /** 找到本次该出块的超级代表地址
   * get ScheduledWitness by slot.
   */
  public ByteString getScheduledWitness(final long slot) {

	  //currentSlot会随着时间间隔3s递增。  计算最新区块距离创世区块的间隔区块数量 + 最新区块距离当前时间点的间隔区块数量
	  //为什么要这样计算区块数量呢？为啥不用区块高度代表数量呢？ 
	  //原因是 可能有的节点没有及时生产出块，这样区块高度就少了一些，只有依据时间计算是最准确的。
    final long currentSlot = getHeadSlot() + slot;

    if (currentSlot < 0) {
      throw new RuntimeException("currentSlot should be positive.");
    }

    int numberActiveWitness = this.getActiveWitnesses().size();
    int singleRepeat = ChainConstant.SINGLE_REPEAT;  // 这是每个代表每次出块的数量。
    if (numberActiveWitness <= 0) {
      throw new RuntimeException("Active Witnesses is null.");
    }
    
    ///witnessIndex表示超级代表的序号. 每一个出块编号都固定的对应一个代表节点序号。
    int witnessIndex = (int) currentSlot % (numberActiveWitness * singleRepeat);
    witnessIndex /= singleRepeat;
    logger.debug("currentSlot:" + currentSlot
        + ", witnessIndex" + witnessIndex
        + ", currentActiveWitnesses size:" + numberActiveWitness);

    //通过序号获得本次该出块的超级代表地址， 只要超级代表列表顺序不变，就表示代表是确定的。
    final ByteString scheduledWitness = this.getActiveWitnesses().get(witnessIndex);
    logger.info("scheduledWitness:" + ByteArray.toHexString(scheduledWitness.toByteArray())
        + ", currentSlot:" + currentSlot);

    return scheduledWitness;
  }

  //计算最新区块距离创世区块的间隔区块数量
  public long getHeadSlot() {
    return (manager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp() 
    		 - getGenesisBlock().getTimeStamp()) / ChainConstant.BLOCK_PRODUCED_INTERVAL;
  }

  /**
   * shuffle witnesses
   */
  public void updateWitnessSchedule() {
//    if (CollectionUtils.isEmpty(getActiveWitnesses())) {
//      throw new RuntimeException("Witnesses is empty");
//    }
//
//    List<ByteString> currentWitsAddress = getCurrentShuffledWitnesses();
//    // TODO  what if the number of witness is not same in different slot.
//    long num = manager.getDynamicPropertiesStore().getLatestBlockHeaderNumber();
//    long time = manager.getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();
//
//    if (num != 0 && num % getActiveWitnesses().isEmpty()) {
//      logger.info("updateWitnessSchedule number:{},HeadBlockTimeStamp:{}", num, time);
//      setCurrentShuffledWitnesses(new RandomGenerator<ByteString>()
//          .shuffle(getActiveWitnesses(), time));
//
//      logger.info(
//          "updateWitnessSchedule,before:{} ", getAddressStringList(currentWitsAddress)
//              + ",\nafter:{} " + getAddressStringList(getCurrentShuffledWitnesses()));
//    }
  }

  /*计算得票
   *参数： votesStore---这是选票仓库
   * */
  private Map<ByteString, Long> countVote(VotesStore votesStore) {
	  
    final Map<ByteString, Long> countWitness = Maps.newHashMap();
    
    //在父类TronStoreWithRevoking中实现了迭代器，本质是数据库中的迭代器
    Iterator<Map.Entry<byte[], VotesCapsule>> dbIterator = votesStore.iterator();

    /*  votesStore选票仓库中存储的是一个个VotesCapsule对象，
     *  VotesCapsule对象中保存的是Votes对象， 
     *  Votes对象保存的是一个投票人对多个候选节点的投票， 分old_votes、new_votes两个列表。
     * */
    long sizeCount = 0;
    while (dbIterator.hasNext()) {
      Entry<byte[], VotesCapsule> next = dbIterator.next();  //取出每一个VotesCapsule对象
      VotesCapsule votes = next.getValue(); 

//      logger.info("there is account ,account address is {}",
//          account.createReadableString());

      // TODO add vote reward
      // long reward = Math.round(sum.get() * this.manager.getDynamicPropertiesStore()
      //    .getVoteRewardRate());
      //account.setBalance(account.getBalance() + reward);
      //accountStore.put(account.createDbKey(), account);

      //使用了old_votes、new_votes列表，只要累计其中的票数(一负一正)就行了。
      votes.getOldVotes().forEach(vote -> {
        //TODO validate witness //active_witness
        ByteString voteAddress = vote.getVoteAddress();
        long voteCount = vote.getVoteCount();
        if (countWitness.containsKey(voteAddress)) {
          countWitness.put(voteAddress, countWitness.get(voteAddress) - voteCount);
        } else {
          countWitness.put(voteAddress, -voteCount);
        }
      });
      votes.getNewVotes().forEach(vote -> {
        //TODO validate witness //active_witness
        ByteString voteAddress = vote.getVoteAddress();
        long voteCount = vote.getVoteCount();
        if (countWitness.containsKey(voteAddress)) {
          countWitness.put(voteAddress, countWitness.get(voteAddress) + voteCount);
        } else {
          countWitness.put(voteAddress, voteCount);
        }
      });

      sizeCount++;   //统计的VotesCapsule对象个数
      //VotesCapsule对象已经统计完了，没有用了，从仓库中删除。以后用户投票时会重新写入新的投票记录。
      votesStore.delete(next.getKey());  
      
    }
    logger.info("there is {} new votes in this epoch", sizeCount);

    return countWitness;
  }

  /**
   * update witness. 更新见证人节点
   */
  public void updateWitness() {
    WitnessStore witnessStore = manager.getWitnessStore();
    VotesStore votesStore = manager.getVotesStore();         //这是选票仓库
    AccountStore accountStore = manager.getAccountStore();   //这是系统中的账户仓库，包含所有用户的地址

    tryRemoveThePowerOfTheGr(); //清除GR的创世票数

    //统计出来表示本轮期间有改变的节点的得票数增减量
    Map<ByteString, Long> countWitness = countVote(votesStore);
    logger.info("\n\n#### updateWitness:  countWitness.isEmpty()= {} \n",countWitness.isEmpty());
    
    //Only possible during the initialization phase

      List<ByteString> currentWits = getActiveWitnesses();

      List<ByteString> newWitnessAddressList = new ArrayList<>();
      witnessStore.getAllWitnesses().forEach(witnessCapsule -> {
          newWitnessAddressList.add(witnessCapsule.getAddress());
      });

      if (!countWitness.isEmpty()) {
	      countWitness.forEach((address, voteCount) -> {
	    	//虽然投票统计出来了，仍然要进行安全检查：先在见证人仓库witnessStore查询是否地址存在；
	    	//然后查询该地址在账户仓库accountStore中是否存在。
	        final WitnessCapsule witnessCapsule = witnessStore.get(StringUtil.createDbKey(address));
	        if (null == witnessCapsule) {
	          logger.warn("witnessCapsule is null.address is {}",
	              StringUtil.createReadableString(address));
	          return;
	        }
	
	        AccountCapsule witnessAccountCapsule = accountStore.get(StringUtil.createDbKey(address));
	        if (witnessAccountCapsule == null) {
	          logger.warn(
	              "witnessAccount[" + StringUtil.createReadableString(address) + "] not exists");
	        } else {
	          //累加票数
	          witnessCapsule.setVoteCount(witnessCapsule.getVoteCount() + voteCount);
	          witnessStore.put(witnessCapsule.createDbKey(), witnessCapsule);
	          logger.info("address is {}  ,countVote is {}", witnessCapsule.createReadableString(),
	              witnessCapsule.getVoteCount());
	        }
	      });
      }

      //根据节点得票数进行从高到低排序
      sortWitness(newWitnessAddressList);
      
      //选择前23个节点作为激活的见证人---超级节点， 后面的就是候选节点
      if (newWitnessAddressList.size() > ChainConstant.MAX_ACTIVE_WITNESS_NUM) {
        setActiveWitnesses(newWitnessAddressList.subList(0, ChainConstant.MAX_ACTIVE_WITNESS_NUM));
      } else {
        setActiveWitnesses(newWitnessAddressList);
      }
      
      //只选择排名前123个候选节点分享奖励
      if (newWitnessAddressList.size() > ChainConstant.WITNESS_STANDBY_LENGTH) {
        payStandbyWitness(newWitnessAddressList.subList(0, ChainConstant.WITNESS_STANDBY_LENGTH));
      } else {
        payStandbyWitness(newWitnessAddressList);
      }

      //选出新的超级节点之后，就要对原来的超级节点代表进行清理了，具体是设置工作开关为false，表示不再出块工作。
      //然后设置新的超级节点 工作开关为true，表示正在工作。
      List<ByteString> newWits = getActiveWitnesses();
      if (witnessSetChanged(currentWits, newWits)) {
        currentWits.forEach(address -> {
          WitnessCapsule witnessCapsule = getWitnesseByAddress(address);
          witnessCapsule.setIsJobs(false);
          witnessStore.put(witnessCapsule.createDbKey(), witnessCapsule);
        });

        newWits.forEach(address -> {
          WitnessCapsule witnessCapsule = getWitnesseByAddress(address);
          witnessCapsule.setIsJobs(true);
          witnessStore.put(witnessCapsule.createDbKey(), witnessCapsule);
        });
      }

      logger.info( "updateWitness,before:{} ", StringUtil.getAddressStringList(currentWits)
              + ",\nafter:{} " + StringUtil.getAddressStringList(newWits));
      
  }

  /*清除GR的创世票数
   *在配置文件中创世区块设定了见证人的初始票数，在按照票数分红时就是一个统计上的缺陷。 根据提案#10就可以不计算在内。 
   *变量初始值为0， 提案执行时修改为1， 清除GR创世票数之后设置为-1.
   */
  public void tryRemoveThePowerOfTheGr() {
    if (manager.getDynamicPropertiesStore().getRemoveThePowerOfTheGr() == 1) {

      WitnessStore witnessStore = manager.getWitnessStore();

      Args.getInstance().getGenesisBlock().getWitnesses().forEach(witnessInGenesisBlock -> {
        WitnessCapsule witnessCapsule = witnessStore.get(witnessInGenesisBlock.getAddress());
        // 净票数 = 当前得票数  - 初始票数
        witnessCapsule.setVoteCount(witnessCapsule.getVoteCount() - witnessInGenesisBlock.getVoteCount());

        witnessStore.put(witnessCapsule.createDbKey(), witnessCapsule);
      });

      manager.getDynamicPropertiesStore().saveRemoveThePowerOfTheGr(-1);
    }
  }

  private static boolean witnessSetChanged(List<ByteString> list1, List<ByteString> list2) {
    return !CollectionUtils.isEqualCollection(list1, list2);
  }

// 参与度
  public int calculateParticipationRate() {
    return manager.getDynamicPropertiesStore().calculateFilledSlotsCount();
  }

  public void dumpParticipationLog() {
    StringBuilder builder = new StringBuilder();
    int[] blockFilledSlots = manager.getDynamicPropertiesStore().getBlockFilledSlots();
    builder.append("dump participation log \n ").append("blockFilledSlots:")
        .append(Arrays.toString(blockFilledSlots)).append(",");
    long headSlot = getHeadSlot();
    builder.append("\n").append(" headSlot:").append(headSlot).append(",");

    List<ByteString> activeWitnesses = getActiveWitnesses();
    activeWitnesses.forEach(a -> {
      WitnessCapsule witnessCapsule = manager.getWitnessStore().get(a.toByteArray());
      builder.append("\n").append(" witness:").append(witnessCapsule.createReadableString())
          .append(",").
          append("latestBlockNum:").append(witnessCapsule.getLatestBlockNum()).append(",").
          append("LatestSlotNum:").append(witnessCapsule.getLatestSlotNum()).append(".");
    });
    logger.debug(builder.toString());
  }

  /*节点排序，步骤：
   * 1先根据每个地址所得票数排序，这是从小到大顺序
   * 2逆序， 变成从大到小
   * 3万一得票数相同，依据地址的hashCode排序，从小到大，再逆序成从大到小顺序。
  */
  private void sortWitness(List<ByteString> list) {
    list.sort(Comparator.comparingLong((ByteString b) -> getWitnesseByAddress(b).getVoteCount())
        .reversed()
        .thenComparing(Comparator.comparingInt(ByteString::hashCode).reversed()));
  }

  //为选中的前123个节点分配奖励
  private void payStandbyWitness(List<ByteString> list) {
    long voteSum = 0;
    //这是节点分享的奖励总数 50400
    long totalPay = manager.getDynamicPropertiesStore().getWitnessStandbyAllowance();
    
    //累计所有节点选票总和
    for (ByteString b : list) {
      voteSum += getWitnesseByAddress(b).getVoteCount();
    }
    if (voteSum > 0) {
    	//根据每个候选节点的选票所占比例计算应得奖励
      for (ByteString b : list) {
        long pay = (long) (getWitnesseByAddress(b).getVoteCount() * ((double) totalPay / voteSum));
        AccountCapsule accountCapsule = manager.getAccountStore().get(b.toByteArray());
        accountCapsule.setAllowance(accountCapsule.getAllowance() + pay);
        manager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
      }
    }

  }

  public boolean isGeneratingBlock() {
    return generatingBlock.get();
  }

  public void setGeneratingBlock(boolean generatingBlock) {
    this.generatingBlock.set(generatingBlock);
  }
}
