package org.bhc.core.services;

import static org.bhc.core.witness.BlockProductionCondition.NOT_MY_TURN;

import com.google.common.collect.Maps;
import com.google.protobuf.ByteString;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.joda.time.DateTime;
import org.bhc.common.application.Application;
import org.bhc.common.application.Service;
import org.bhc.common.application.TronApplicationContext;
import org.bhc.common.backup.BackupManager;
import org.bhc.common.backup.BackupManager.BackupStatusEnum;
import org.bhc.common.backup.BackupServer;
import org.bhc.common.crypto.ECKey;
import org.bhc.common.utils.ByteArray;
import org.bhc.common.utils.StringUtil;
import org.bhc.core.capsule.AccountCapsule;
import org.bhc.core.capsule.BlockCapsule;
import org.bhc.core.capsule.WitnessCapsule;
import org.bhc.core.config.Parameter.ChainConstant;
import org.bhc.core.config.args.Args;
import org.bhc.core.db.Manager;
import org.bhc.core.exception.AccountResourceInsufficientException;
import org.bhc.core.exception.ContractExeException;
import org.bhc.core.exception.ContractValidateException;
import org.bhc.core.exception.TronException;
import org.bhc.core.exception.UnLinkedBlockException;
import org.bhc.core.exception.ValidateScheduleException;
import org.bhc.core.exception.ValidateSignatureException;
import org.bhc.core.net.TronNetService;
import org.bhc.core.net.message.BlockMessage;
import org.bhc.core.witness.BlockProductionCondition;
import org.bhc.core.witness.WitnessController;

@Slf4j(topic = "witness")
public class WitnessService implements Service {

  private static final int MIN_PARTICIPATION_RATE = Args.getInstance().getMinParticipationRate(); // MIN_PARTICIPATION_RATE * 1%
  private static final int PRODUCE_TIME_OUT = 500; // ms
  @Getter
  private static volatile boolean needSyncCheck = Args.getInstance().isNeedSyncCheck();

  private Application tronApp;
  
  @Getter
  protected Map<ByteString, WitnessCapsule> localWitnessStateMap = Maps.newHashMap(); //  <witnessAccountAddress,WitnessCapsule>
  
  private Thread generateThread;

  private volatile boolean isRunning = false;
  private Map<ByteString, byte[]> privateKeyMap      = Maps.newHashMap();//<witnessAccountAddress,privateKey>
  private Map<byte[], byte[]> privateKeyToAddressMap = Maps.newHashMap();//<privateKey,witnessPermissionAccountAddress>

  private Manager manager;

  private WitnessController controller;

  private TronApplicationContext context;

  private BackupManager backupManager;

  private BackupServer backupServer;

  private TronNetService tronNetService;

  private AtomicInteger dupBlockCount = new AtomicInteger(0);
  private AtomicLong dupBlockTime = new AtomicLong(0);
  
  private long blockCycle =  //轮流间隔是3*27s
      ChainConstant.BLOCK_PRODUCED_INTERVAL * ChainConstant.MAX_ACTIVE_WITNESS_NUM;

  /**
   * Construction method.
   */
  public WitnessService(Application tronApp, TronApplicationContext context) {
    this.tronApp = tronApp;
    this.context = context;
    backupManager = context.getBean(BackupManager.class);
    backupServer = context.getBean(BackupServer.class);
    tronNetService = context.getBean(TronNetService.class);
    generateThread = new Thread(scheduleProductionLoop);
    manager = tronApp.getDbManager();
    manager.setWitnessService(this);
    controller = manager.getWitnessController();
    new Thread(() -> {
      while (needSyncCheck) {
        try {
          Thread.sleep(100);
        } catch (Exception e) {
        }
      }
      backupServer.initServer();
    }).start();
  }

  /**
   * Cycle thread to generate blocks
   */
  private Runnable scheduleProductionLoop =
      () -> {
        if (localWitnessStateMap == null || localWitnessStateMap.keySet().isEmpty()) {
          logger.error("LocalWitnesses is null");
          return;
        }

        while (isRunning) {
          try {
	            if (this.needSyncCheck) {
	              Thread.sleep(500L);
	            } else {
	              DateTime time = DateTime.now();
	              
	              //先计算%， 这段含义取得是在3秒的整数倍时间点上生成区块， 不够的就休眠等待一段时间。
	              long timeToNextSecond = ChainConstant.BLOCK_PRODUCED_INTERVAL
	                  - (time.getSecondOfMinute() * 1000 + time.getMillisOfSecond()) % ChainConstant.BLOCK_PRODUCED_INTERVAL;
	              if (timeToNextSecond < 50L) {
	                timeToNextSecond = timeToNextSecond + ChainConstant.BLOCK_PRODUCED_INTERVAL;
	              }
	              DateTime nextTime = time.plus(timeToNextSecond);
	              logger.info("ProductionLoop sleep : " + timeToNextSecond + " ms,next time:" + nextTime);
	              Thread.sleep(timeToNextSecond);
	            }
	            
	            this.blockProductionLoop();  //不管等待同步还是等待延时，都要执行这个函数
	            
          } catch (InterruptedException ex) {
            logger.info("ProductionLoop interrupted");
          } catch (Exception ex) {
            logger.error("unknown exception happened in witness loop", ex);
          } catch (Throwable throwable) {
            logger.error("unknown throwable happened in witness loop", throwable);
          }
        }
      };

  /**
   * Loop to generate blocks
   */
  private void blockProductionLoop() throws InterruptedException {
    BlockProductionCondition result = this.tryProduceBlock();

    if (result == null) {
      logger.warn("Result is null");
      return;
    }

    if (result.ordinal() <= NOT_MY_TURN.ordinal()) {
      logger.debug(result.toString());
    } else {
      logger.info(result.toString());
    }
  }

  /**
   * Generate and broadcast blocks
   */
  private BlockProductionCondition tryProduceBlock() throws InterruptedException {
    logger.info("Try Produce Block");
    
    long now = DateTime.now().getMillis() + 50L;
    
    //检查同步状态，根据下个区块的时间戳是否大于当前时间决定同步结束了吗。
    if (this.needSyncCheck) {
      long nexSlotTime = controller.getSlotTime(1);
      if (nexSlotTime > now) { // check sync during first loop， 终于同步完成了，等待一段时间，把生成时间取整化
        needSyncCheck = false;
        Thread.sleep(nexSlotTime - now); //Processing Time Drift later
        now = DateTime.now().getMillis();
      } else {
    	  //这里是说明靠区块的时间戳得到下一个区块的生成时间， 如果不到当前时间，说明还没有同步完成。
        logger.debug("Not sync ,now:{},headBlockTime:{},headBlockNumber:{},headBlockId:{}",
            new DateTime(now),
            new DateTime(this.tronApp.getDbManager().getDynamicPropertiesStore()
                .getLatestBlockHeaderTimestamp()),
            this.tronApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
            this.tronApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderHash());
        return BlockProductionCondition.NOT_SYNCED;
      }
    }

    if (!backupManager.getStatus().equals(BackupStatusEnum.MASTER)) {
      return BlockProductionCondition.BACKUP_STATUS_IS_NOT_MASTER;
    }

    //检测到网络中出现两个超级代表，IP不同，但是账户相同。就强制等待随机n轮时间（每轮是3*27秒）不能出块。
    if (dupWitnessCheck()) {
      return BlockProductionCondition.DUP_WITNESS;
    }

    //检查自己生成区块的参与度
    final int participation = this.controller.calculateParticipationRate();
    if (participation < MIN_PARTICIPATION_RATE) {  //参与度不得低于15%
      logger.warn(
          "Participation[" + participation + "] <  MIN_PARTICIPATION_RATE[" + MIN_PARTICIPATION_RATE
              + "]");

      if (logger.isDebugEnabled()) {
        this.controller.dumpParticipationLog();
      }

      return BlockProductionCondition.LOW_PARTICIPATION;
    }

    //未经选举的 的代表，野节点???
    if (!controller.activeWitnessesContain(this.getLocalWitnessStateMap().keySet())) {
      logger.info("Unelected. Elected Witnesses: {}",
          StringUtil.getAddressStringList(controller.getActiveWitnesses()));
      return BlockProductionCondition.UNELECTED;
    }

    try {

      BlockCapsule block;

      synchronized (tronApp.getDbManager()) {
    	logger.info("now:" + now);
        long slot = controller.getSlotAtTime(now);   //表示当前时间点now与区块时间戳的差距， 差了多少个区块数
        logger.info("Slot:" + slot);
        if (slot == 0) {   //当前时间比区块时间晚，此时不能生成区块
          logger.info("Not time yet,now:{},headBlockTime:{},headBlockNumber:{},headBlockId:{}",
              new DateTime(now),
              new DateTime(
                  this.tronApp.getDbManager().getDynamicPropertiesStore()
                      .getLatestBlockHeaderTimestamp()),
              this.tronApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
              this.tronApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderHash());
          return BlockProductionCondition.NOT_TIME_YET;
        }

        // 当前时间肯定大于最新区块时间戳，这样才能生产区块。
        if (now < controller.getManager().getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()) {
          logger.warn("have a timestamp:{} less than or equal to the previous block:{}",
              new DateTime(now), new DateTime(
                  this.tronApp.getDbManager().getDynamicPropertiesStore()
                      .getLatestBlockHeaderTimestamp()));
          return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
        }
        
        
        //slot表示当前区块间隔，一般是1。
        //根据超级代表列表数量，轮流出块，因此每个块都对应这具体的一个超级代表生产者scheduledWitness。
        //如果scheduledWitness与本机不一致，就表示没轮到自己生产，应该由别人生产，自己这个周期不生产区块。
        final ByteString scheduledWitness = controller.getScheduledWitness(slot);
        if (!this.getLocalWitnessStateMap().containsKey(scheduledWitness)) {
          logger.info("It's not my turn, ScheduledWitness[{}],slot[{}],abSlot[{}],",
              ByteArray.toHexString(scheduledWitness.toByteArray()), slot,
              controller.getAbSlotAtTime(now));
          return NOT_MY_TURN;
        }

        //这里会超时吗？   理论上运行到此处，scheduledTime应该等于now， 如果发生大于情况，啥原因导致的呢？？？
        long scheduledTime = controller.getSlotTime(slot);
        if (scheduledTime - now > PRODUCE_TIME_OUT) {
          return BlockProductionCondition.LAG;
        }

        //该自己生产，但是没发现超级代表的私钥， 这怎么可能呢？
        if (!privateKeyMap.containsKey(scheduledWitness)) {
          return BlockProductionCondition.NO_PRIVATE_KEY;
        }

        controller.getManager().lastHeadBlockIsMaintenance(); //这句话没有用处！！！！！

        controller.setGeneratingBlock(true);   //正在生成区块状态为true

        block = generateBlock(scheduledTime, scheduledWitness, controller.lastHeadBlockIsMaintenance());
      }

      if (block == null) {
        logger.warn("exception when generate block");
        return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
      }

      //检查生产区块是否超时3秒的 50%  
      int blockProducedTimeOut = Args.getInstance().getBlockProducedTimeOut();
      long timeout = Math
          .min(ChainConstant.BLOCK_PRODUCED_INTERVAL * blockProducedTimeOut / 100 + 500,
              ChainConstant.BLOCK_PRODUCED_INTERVAL);
      if (DateTime.now().getMillis() - now > timeout) {  //说明生产区块耗时超过2000ms了，判定为失败。
        logger.warn("Task timeout ( > {}ms) startTime:{},endTime:{}", timeout, new DateTime(now),
            DateTime.now());
        tronApp.getDbManager().eraseBlock();
        return BlockProductionCondition.TIME_OUT;
      }

      logger.info(
          "Produce block successfully, blockNumber:{}, abSlot[{}], blockId:{}, transactionSize:{}, blockTime:{}, parentBlockId:{}",
          block.getNum(), controller.getAbSlotAtTime(now), block.getBlockId(),
          block.getTransactions().size(),
          new DateTime(block.getTimeStamp()),
          block.getParentHash());

      //必须广播区块
      broadcastBlock(block);
      return BlockProductionCondition.PRODUCED;
      
    } catch (TronException e) {
      logger.error(e.getMessage(), e);
      return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
    } finally {
      controller.setGeneratingBlock(false);  //正在生成区块状态为false,  生成结束
    }
  }

  //Verify that the private key corresponds to the witness permission
  public boolean validateWitnessPermission(ByteString scheduledWitness) {
    if (manager.getDynamicPropertiesStore().getAllowMultiSign() == 1) {
      byte[] privateKey = privateKeyMap.get(scheduledWitness);
      byte[] witnessPermissionAddress = privateKeyToAddressMap.get(privateKey);
      AccountCapsule witnessAccount = manager.getAccountStore()
          .get(scheduledWitness.toByteArray());
      if (!Arrays.equals(witnessPermissionAddress, witnessAccount.getWitnessPermissionAddress())) {
        return false;
      }
    }
    return true;
  }

  //广播区块消息
  private void broadcastBlock(BlockCapsule block) {
    try {
      tronNetService.broadcast(new BlockMessage(block.getData()));
    } catch (Exception ex) {
      throw new RuntimeException("BroadcastBlock error");
    }
  }

  private BlockCapsule generateBlock(long when, ByteString witnessAddress, Boolean lastHeadBlockIsMaintenance)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      UnLinkedBlockException, ValidateScheduleException, AccountResourceInsufficientException {
    return tronApp.getDbManager().generateBlock(this.localWitnessStateMap.get(witnessAddress), when,
        this.privateKeyMap.get(witnessAddress), lastHeadBlockIsMaintenance, true);
  }

  private boolean dupWitnessCheck() {
	System.out.println("----- dupWitnessCheck: 000 dupBlockCount.get()="+dupBlockCount.get());  
    if (dupBlockCount.get() == 0) {
      return false;
    }
    System.out.println("----- dupWitnessCheck: 111 ");  
    //重复块 时间间隔超过n*3*27秒就清空， 没超过指定时间返回true
    if (System.currentTimeMillis() - dupBlockTime.get() > dupBlockCount.get() * blockCycle) {
      dupBlockCount.set(0);
      return false;
    }
    System.out.println("----- dupWitnessCheck: 222  return true "); 
    return true;
  }

  //当网络中出现两个超级代表，IP不同，但是账户相同，会出现：收到不是本节点生产的，但是见证人账户相同的区块， 这就是矛盾。
  //一旦出现，就设置dupBlockCount，表示本机随机n轮时间（每轮是3*27秒）。
  //之后 在自己出块时发现dupBlockCount非0，在到达指定的n轮时间之前一直返回true， 也就是等待n轮时间不出块。之后超过时间段就恢复。
  public void checkDupWitness(BlockCapsule block) {
    //自己生产的区块无需检测
	if (block.generatedByMyself) {
      return;
    }

    //同步流程状态，直接保存区块数据， 不需要检测。
    if (needSyncCheck) {
      return;
    }

    //区块的时间戳距离当前时间超过3秒，表示该区块已过时，不用检测了。
    if (System.currentTimeMillis() - block.getTimeStamp() > ChainConstant.BLOCK_PRODUCED_INTERVAL) {
      return;
    }

    //本机私钥表中没有包含该区块的见证人地址，说明不可能重复见证人，直接返回。
    if (!privateKeyMap.containsKey(block.getWitnessAddress())) {
      return;
    }

    if (backupManager.getStatus() != BackupStatusEnum.MASTER) {
      return;
    }

    System.out.println("---checkDupWitness: 000  dupBlockCount.get()="+ dupBlockCount.get());
     //双重Block计数，  初始化计数器是零，就设置成随机数[0,9]，不为零就设置为10
    if (dupBlockCount.get() == 0) {
      dupBlockCount.set(new Random().nextInt(10));
    } else {
      dupBlockCount.set(10);
    }
    System.out.println("---checkDupWitness: 111  dupBlockCount.get()="+ dupBlockCount.get());
    dupBlockTime.set(System.currentTimeMillis()); //重复区块的当前时间。
    System.out.println("---checkDupWitness: 222  dupBlockTime.get()="+ dupBlockTime.get());
    logger.warn("Dup block produced: {}", block);
  }

  /**
   * Initialize the local witnesses
   */
  @Override
  public void init() {

    if (Args.getInstance().getLocalWitnesses().getPrivateKeys().size() == 0) {
      return;
    }

    byte[] privateKey = ByteArray.fromHexString(Args.getInstance().getLocalWitnesses().getPrivateKey());
    byte[] witnessAccountAddress = Args.getInstance().getLocalWitnesses().getWitnessAccountAddress();
    System.out.println("----222 witnessAccountAddress="+witnessAccountAddress.toString());
    
    //This address does not need to have an account
    byte[] privateKeyAccountAddress = ECKey.fromPrivate(privateKey).getAddress();
    System.out.println("----333 privateKeyAccountAddress="+privateKeyAccountAddress.toString());

    WitnessCapsule witnessCapsule = this.tronApp.getDbManager().getWitnessStore().get(witnessAccountAddress);
    // need handle init witness
    if (null == witnessCapsule) {
      logger.warn("WitnessCapsule[" + witnessAccountAddress + "] is not in witnessStore");
      witnessCapsule = new WitnessCapsule(ByteString.copyFrom(witnessAccountAddress));
    }  
    System.out.println("----444 witnessCapsule.getAddress()="+witnessCapsule.getAddress().toString());
    this.privateKeyMap.put(witnessCapsule.getAddress(), privateKey);  //只有一次，保存的是本节点启动时指定的超级代表私钥。
    this.localWitnessStateMap.put(witnessCapsule.getAddress(), witnessCapsule);
    this.privateKeyToAddressMap.put(privateKey, privateKeyAccountAddress);
  }

  @Override
  public void init(Args args) {
    //this.privateKey = args.getPrivateKeys();
    init();
  }

  @Override
  public void start() {
    isRunning = true;
    generateThread.start();

  }

  @Override
  public void stop() {
    isRunning = false;
    generateThread.interrupt();
  }
}
