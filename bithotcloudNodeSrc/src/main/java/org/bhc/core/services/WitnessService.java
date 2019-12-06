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
  
  private long blockCycle =  //���������3*27s
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
	              
	              //�ȼ���%�� ��κ���ȡ������3���������ʱ������������飬 �����ľ����ߵȴ�һ��ʱ�䡣
	              long timeToNextSecond = ChainConstant.BLOCK_PRODUCED_INTERVAL
	                  - (time.getSecondOfMinute() * 1000 + time.getMillisOfSecond()) % ChainConstant.BLOCK_PRODUCED_INTERVAL;
	              if (timeToNextSecond < 50L) {
	                timeToNextSecond = timeToNextSecond + ChainConstant.BLOCK_PRODUCED_INTERVAL;
	              }
	              DateTime nextTime = time.plus(timeToNextSecond);
	              logger.info("ProductionLoop sleep : " + timeToNextSecond + " ms,next time:" + nextTime);
	              Thread.sleep(timeToNextSecond);
	            }
	            
	            this.blockProductionLoop();  //���ܵȴ�ͬ�����ǵȴ���ʱ����Ҫִ���������
	            
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
    
    //���ͬ��״̬�������¸������ʱ����Ƿ���ڵ�ǰʱ�����ͬ����������
    if (this.needSyncCheck) {
      long nexSlotTime = controller.getSlotTime(1);
      if (nexSlotTime > now) { // check sync during first loop�� ����ͬ������ˣ��ȴ�һ��ʱ�䣬������ʱ��ȡ����
        needSyncCheck = false;
        Thread.sleep(nexSlotTime - now); //Processing Time Drift later
        now = DateTime.now().getMillis();
      } else {
    	  //������˵���������ʱ����õ���һ�����������ʱ�䣬 ���������ǰʱ�䣬˵����û��ͬ����ɡ�
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

    //��⵽�����г���������������IP��ͬ�������˻���ͬ����ǿ�Ƶȴ����n��ʱ�䣨ÿ����3*27�룩���ܳ��顣
    if (dupWitnessCheck()) {
      return BlockProductionCondition.DUP_WITNESS;
    }

    //����Լ���������Ĳ����
    final int participation = this.controller.calculateParticipationRate();
    if (participation < MIN_PARTICIPATION_RATE) {  //����Ȳ��õ���15%
      logger.warn(
          "Participation[" + participation + "] <  MIN_PARTICIPATION_RATE[" + MIN_PARTICIPATION_RATE
              + "]");

      if (logger.isDebugEnabled()) {
        this.controller.dumpParticipationLog();
      }

      return BlockProductionCondition.LOW_PARTICIPATION;
    }

    //δ��ѡ�ٵ� �Ĵ���Ұ�ڵ�???
    if (!controller.activeWitnessesContain(this.getLocalWitnessStateMap().keySet())) {
      logger.info("Unelected. Elected Witnesses: {}",
          StringUtil.getAddressStringList(controller.getActiveWitnesses()));
      return BlockProductionCondition.UNELECTED;
    }

    try {

      BlockCapsule block;

      synchronized (tronApp.getDbManager()) {
    	logger.info("now:" + now);
        long slot = controller.getSlotAtTime(now);   //��ʾ��ǰʱ���now������ʱ����Ĳ�࣬ ���˶��ٸ�������
        logger.info("Slot:" + slot);
        if (slot == 0) {   //��ǰʱ�������ʱ������ʱ������������
          logger.info("Not time yet,now:{},headBlockTime:{},headBlockNumber:{},headBlockId:{}",
              new DateTime(now),
              new DateTime(
                  this.tronApp.getDbManager().getDynamicPropertiesStore()
                      .getLatestBlockHeaderTimestamp()),
              this.tronApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
              this.tronApp.getDbManager().getDynamicPropertiesStore().getLatestBlockHeaderHash());
          return BlockProductionCondition.NOT_TIME_YET;
        }

        // ��ǰʱ��϶�������������ʱ��������������������顣
        if (now < controller.getManager().getDynamicPropertiesStore().getLatestBlockHeaderTimestamp()) {
          logger.warn("have a timestamp:{} less than or equal to the previous block:{}",
              new DateTime(now), new DateTime(
                  this.tronApp.getDbManager().getDynamicPropertiesStore()
                      .getLatestBlockHeaderTimestamp()));
          return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
        }
        
        
        //slot��ʾ��ǰ��������һ����1��
        //���ݳ��������б��������������飬���ÿ���鶼��Ӧ������һ����������������scheduledWitness��
        //���scheduledWitness�뱾����һ�£��ͱ�ʾû�ֵ��Լ�������Ӧ���ɱ����������Լ�������ڲ��������顣
        final ByteString scheduledWitness = controller.getScheduledWitness(slot);
        if (!this.getLocalWitnessStateMap().containsKey(scheduledWitness)) {
          logger.info("It's not my turn, ScheduledWitness[{}],slot[{}],abSlot[{}],",
              ByteArray.toHexString(scheduledWitness.toByteArray()), slot,
              controller.getAbSlotAtTime(now));
          return NOT_MY_TURN;
        }

        //����ᳬʱ��   ���������е��˴���scheduledTimeӦ�õ���now�� ����������������ɶԭ���µ��أ�����
        long scheduledTime = controller.getSlotTime(slot);
        if (scheduledTime - now > PRODUCE_TIME_OUT) {
          return BlockProductionCondition.LAG;
        }

        //���Լ�����������û���ֳ��������˽Կ�� ����ô�����أ�
        if (!privateKeyMap.containsKey(scheduledWitness)) {
          return BlockProductionCondition.NO_PRIVATE_KEY;
        }

        controller.getManager().lastHeadBlockIsMaintenance(); //��仰û���ô�����������

        controller.setGeneratingBlock(true);   //������������״̬Ϊtrue

        block = generateBlock(scheduledTime, scheduledWitness, controller.lastHeadBlockIsMaintenance());
      }

      if (block == null) {
        logger.warn("exception when generate block");
        return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
      }

      //������������Ƿ�ʱ3��� 50%  
      int blockProducedTimeOut = Args.getInstance().getBlockProducedTimeOut();
      long timeout = Math
          .min(ChainConstant.BLOCK_PRODUCED_INTERVAL * blockProducedTimeOut / 100 + 500,
              ChainConstant.BLOCK_PRODUCED_INTERVAL);
      if (DateTime.now().getMillis() - now > timeout) {  //˵�����������ʱ����2000ms�ˣ��ж�Ϊʧ�ܡ�
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

      //����㲥����
      broadcastBlock(block);
      return BlockProductionCondition.PRODUCED;
      
    } catch (TronException e) {
      logger.error(e.getMessage(), e);
      return BlockProductionCondition.EXCEPTION_PRODUCING_BLOCK;
    } finally {
      controller.setGeneratingBlock(false);  //������������״̬Ϊfalse,  ���ɽ���
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

  //�㲥������Ϣ
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
    //�ظ��� ʱ��������n*3*27�����գ� û����ָ��ʱ�䷵��true
    if (System.currentTimeMillis() - dupBlockTime.get() > dupBlockCount.get() * blockCycle) {
      dupBlockCount.set(0);
      return false;
    }
    System.out.println("----- dupWitnessCheck: 222  return true "); 
    return true;
  }

  //�������г���������������IP��ͬ�������˻���ͬ������֣��յ����Ǳ��ڵ������ģ����Ǽ�֤���˻���ͬ�����飬 �����ì�ܡ�
  //һ�����֣�������dupBlockCount����ʾ�������n��ʱ�䣨ÿ����3*27�룩��
  //֮�� ���Լ�����ʱ����dupBlockCount��0���ڵ���ָ����n��ʱ��֮ǰһֱ����true�� Ҳ���ǵȴ�n��ʱ�䲻���顣֮�󳬹�ʱ��ξͻָ���
  public void checkDupWitness(BlockCapsule block) {
    //�Լ�����������������
	if (block.generatedByMyself) {
      return;
    }

    //ͬ������״̬��ֱ�ӱ����������ݣ� ����Ҫ��⡣
    if (needSyncCheck) {
      return;
    }

    //�����ʱ������뵱ǰʱ�䳬��3�룬��ʾ�������ѹ�ʱ�����ü���ˡ�
    if (System.currentTimeMillis() - block.getTimeStamp() > ChainConstant.BLOCK_PRODUCED_INTERVAL) {
      return;
    }

    //����˽Կ����û�а���������ļ�֤�˵�ַ��˵���������ظ���֤�ˣ�ֱ�ӷ��ء�
    if (!privateKeyMap.containsKey(block.getWitnessAddress())) {
      return;
    }

    if (backupManager.getStatus() != BackupStatusEnum.MASTER) {
      return;
    }

    System.out.println("---checkDupWitness: 000  dupBlockCount.get()="+ dupBlockCount.get());
     //˫��Block������  ��ʼ�����������㣬�����ó������[0,9]����Ϊ�������Ϊ10
    if (dupBlockCount.get() == 0) {
      dupBlockCount.set(new Random().nextInt(10));
    } else {
      dupBlockCount.set(10);
    }
    System.out.println("---checkDupWitness: 111  dupBlockCount.get()="+ dupBlockCount.get());
    dupBlockTime.set(System.currentTimeMillis()); //�ظ�����ĵ�ǰʱ�䡣
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
    this.privateKeyMap.put(witnessCapsule.getAddress(), privateKey);  //ֻ��һ�Σ�������Ǳ��ڵ�����ʱָ���ĳ�������˽Կ��
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
