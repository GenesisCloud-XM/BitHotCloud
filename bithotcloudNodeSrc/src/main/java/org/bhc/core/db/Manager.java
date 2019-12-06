package org.bhc.core.db;

import static org.bhc.core.config.Parameter.ChainConstant.SOLIDIFIED_THRESHOLD;
import static org.bhc.core.config.Parameter.NodeConstant.MAX_TRANSACTION_PENDING;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.primitives.Longs;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import javafx.util.Pair;
import javax.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.joda.time.DateTime;
import org.spongycastle.util.encoders.Hex;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.bhc.common.logsfilter.EventPluginLoader;
import org.bhc.common.logsfilter.FilterQuery;
import org.bhc.common.logsfilter.capsule.BlockLogTriggerCapsule;
import org.bhc.common.logsfilter.capsule.ContractEventTriggerCapsule;
import org.bhc.common.logsfilter.capsule.ContractLogTriggerCapsule;
import org.bhc.common.logsfilter.capsule.TransactionLogTriggerCapsule;
import org.bhc.common.logsfilter.capsule.TriggerCapsule;
import org.bhc.common.logsfilter.trigger.ContractLogTrigger;
import org.bhc.common.logsfilter.trigger.ContractTrigger;
import org.bhc.common.overlay.discover.node.Node;
import org.bhc.common.runtime.config.VMConfig;
import org.bhc.common.runtime.vm.LogEventWrapper;
import org.bhc.common.utils.ByteArray;
import org.bhc.common.utils.ForkController;
import org.bhc.common.utils.SessionOptional;
import org.bhc.common.utils.Sha256Hash;
import org.bhc.common.utils.StringUtil;
import org.bhc.core.Constant;
import org.bhc.core.capsule.AccountCapsule;
import org.bhc.core.capsule.BlockCapsule;
import org.bhc.core.capsule.BlockCapsule.BlockId;
import org.bhc.core.capsule.BytesCapsule;
import org.bhc.core.capsule.ExchangeCapsule;
import org.bhc.core.capsule.TransactionCapsule;
import org.bhc.core.capsule.TransactionInfoCapsule;
import org.bhc.core.capsule.WitnessCapsule;
import org.bhc.core.capsule.utils.BlockUtil;
import org.bhc.core.config.Parameter.ChainConstant;
import org.bhc.core.config.args.Args;
import org.bhc.core.config.args.GenesisBlock;
import org.bhc.core.db.KhaosDatabase.KhaosBlock;
import org.bhc.core.db.api.AssetUpdateHelper;
import org.bhc.core.db2.core.ISession;
import org.bhc.core.db2.core.ITronChainBase;
import org.bhc.core.db2.core.SnapshotManager;
import org.bhc.core.exception.AccountResourceInsufficientException;
import org.bhc.core.exception.BadBlockException;
import org.bhc.core.exception.BadItemException;
import org.bhc.core.exception.BadNumberBlockException;
import org.bhc.core.exception.BalanceInsufficientException;
import org.bhc.core.exception.ContractExeException;
import org.bhc.core.exception.ContractSizeNotEqualToOneException;
import org.bhc.core.exception.ContractValidateException;
import org.bhc.core.exception.DupTransactionException;
import org.bhc.core.exception.HeaderNotFound;
import org.bhc.core.exception.ItemNotFoundException;
import org.bhc.core.exception.NonCommonBlockException;
import org.bhc.core.exception.ReceiptCheckErrException;
import org.bhc.core.exception.TaposException;
import org.bhc.core.exception.TooBigTransactionException;
import org.bhc.core.exception.TooBigTransactionResultException;
import org.bhc.core.exception.TransactionExpirationException;
import org.bhc.core.exception.UnLinkedBlockException;
import org.bhc.core.exception.VMIllegalException;
import org.bhc.core.exception.ValidateScheduleException;
import org.bhc.core.exception.ValidateSignatureException;
import org.bhc.core.services.WitnessService;
import org.bhc.core.witness.ProposalController;
import org.bhc.core.witness.WitnessController;
import org.bhc.protos.Protocol.AccountType;
import org.bhc.protos.Protocol.Transaction;
import org.bhc.protos.Protocol.Transaction.Contract;


@Slf4j(topic = "DB")
@Component
public class Manager {

  // db store
  @Autowired
  private AccountStore accountStore;
  @Autowired
  private TransactionStore transactionStore;
  @Autowired(required = false)
  private TransactionCache transactionCache;
  @Autowired
  private BlockStore blockStore;
  @Autowired
  private WitnessStore witnessStore;
  @Autowired
  private AssetIssueStore assetIssueStore;
  @Autowired
  private AssetIssueV2Store assetIssueV2Store;
  @Autowired
  private DynamicPropertiesStore dynamicPropertiesStore;
  @Autowired
  @Getter
  private BlockIndexStore blockIndexStore;
  @Autowired
  private AccountIdIndexStore accountIdIndexStore;
  @Autowired
  private AccountIndexStore accountIndexStore;
  @Autowired
  private WitnessScheduleStore witnessScheduleStore;  //保存超级节点列表的仓库
  @Autowired
  private RecentBlockStore recentBlockStore;
  @Autowired
  private VotesStore votesStore;
  @Autowired
  private ProposalStore proposalStore;
  @Autowired
  private ExchangeStore exchangeStore;
  @Autowired
  private ExchangeV2Store exchangeV2Store;
  @Autowired
  private TransactionHistoryStore transactionHistoryStore;
  @Autowired
  private CodeStore codeStore;
  @Autowired
  private ContractStore contractStore;
  @Autowired
  private DelegatedResourceStore delegatedResourceStore;
  @Autowired
  private DelegatedResourceAccountIndexStore delegatedResourceAccountIndexStore;
  @Autowired
  @Getter
  private StorageRowStore storageRowStore;

  // for network
  @Autowired
  private PeersStore peersStore;//数据库，用于p2p节点的数据保存及读取

  @Autowired
  private KhaosDatabase khaosDb;


  private BlockCapsule genesisBlock;
  @Getter
  @Autowired
  private RevokingDatabase revokingStore;

  @Getter
  private SessionOptional session = SessionOptional.instance();

  @Getter
  @Setter
  private boolean isSyncMode;

  @Getter
  @Setter
  private String netType;

  @Getter
  @Setter
  private WitnessService witnessService;

  @Getter
  @Setter
  private WitnessController witnessController;

  @Getter
  @Setter
  private ProposalController proposalController;

  private ExecutorService validateSignService;

  private boolean isRunRepushThread = true;

  private boolean isRunTriggerCapsuleProcessThread = true;

  private long latestSolidifiedBlockNumber;

  @Getter
  @Setter
  public boolean eventPluginLoaded = false;

  private BlockingQueue<TransactionCapsule> pushTransactionQueue = new LinkedBlockingQueue<>();

  @Getter
  private Cache<Sha256Hash, Boolean> transactionIdCache = CacheBuilder
      .newBuilder().maximumSize(100_000).recordStats().build();

  @Getter
  private ForkController forkController = ForkController.instance();

  private Set<String> ownerAddressSet = new HashSet<>();  //合约所有者地址集合

  public WitnessStore getWitnessStore() {
    return this.witnessStore;
  }

  public boolean needToUpdateAsset() {
    return getDynamicPropertiesStore().getTokenUpdateDone() == 0L;
  }

  public DynamicPropertiesStore getDynamicPropertiesStore() {
    return this.dynamicPropertiesStore;
  }

  public void setDynamicPropertiesStore(final DynamicPropertiesStore dynamicPropertiesStore) {
    this.dynamicPropertiesStore = dynamicPropertiesStore;
  }

  public WitnessScheduleStore getWitnessScheduleStore() {
    return this.witnessScheduleStore;
  }

  public void setWitnessScheduleStore(final WitnessScheduleStore witnessScheduleStore) {
    this.witnessScheduleStore = witnessScheduleStore;
  }


  public DelegatedResourceStore getDelegatedResourceStore() {
    return delegatedResourceStore;
  }

  public DelegatedResourceAccountIndexStore getDelegatedResourceAccountIndexStore() {
    return delegatedResourceAccountIndexStore;
  }

  public CodeStore getCodeStore() {
    return codeStore;
  }

  public ContractStore getContractStore() {
    return contractStore;
  }

  public VotesStore getVotesStore() {
    return this.votesStore;
  }

  public ProposalStore getProposalStore() {
    return this.proposalStore;
  }

  public ExchangeStore getExchangeStore() {
    return this.exchangeStore;
  }

  public ExchangeV2Store getExchangeV2Store() {
    return this.exchangeV2Store;
  }

  public ExchangeStore getExchangeStoreFinal() {
    if (getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      return getExchangeStore();
    } else {
      return getExchangeV2Store();
    }
  }

  public void putExchangeCapsule(ExchangeCapsule exchangeCapsule) {
    if (getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      getExchangeStore().put(exchangeCapsule.createDbKey(), exchangeCapsule);
      ExchangeCapsule exchangeCapsuleV2 = new ExchangeCapsule(exchangeCapsule.getData());
      exchangeCapsuleV2.resetTokenWithID(this);
      getExchangeV2Store().put(exchangeCapsuleV2.createDbKey(), exchangeCapsuleV2);
    } else {
      getExchangeV2Store().put(exchangeCapsule.createDbKey(), exchangeCapsule);
    }
  }

  public List<TransactionCapsule> getPendingTransactions() {
    return this.pendingTransactions;
  }

  public List<TransactionCapsule> getPoppedTransactions() {
    return this.popedTransactions;
  }

  public BlockingQueue<TransactionCapsule> getRepushTransactions() {
    return repushTransactions;
  }

  // transactions cache
  private List<TransactionCapsule> pendingTransactions;  //未决交易列表

  // transactions popped
  private List<TransactionCapsule> popedTransactions =
      Collections.synchronizedList(Lists.newArrayList());

  // the capacity is equal to Integer.MAX_VALUE default
  private BlockingQueue<TransactionCapsule> repushTransactions;  //重推的交易列表

  private BlockingQueue<TriggerCapsule> triggerCapsuleQueue;

  // for test only
  public List<ByteString> getWitnesses() {
    return witnessController.getActiveWitnesses();
  }

  // for test only
  public void addWitness(final ByteString address) {
    List<ByteString> witnessAddresses = witnessController.getActiveWitnesses();
    witnessAddresses.add(address);
    witnessController.setActiveWitnesses(witnessAddresses);
  }

  public BlockCapsule getHead() throws HeaderNotFound {
    List<BlockCapsule> blocks = getBlockStore().getBlockByLatestNum(1);
    if (CollectionUtils.isNotEmpty(blocks)) {
      return blocks.get(0);
    } else {
      logger.info("Header block Not Found");
      throw new HeaderNotFound("Header block Not Found");
    }
  }

  public synchronized BlockId getHeadBlockId() {
    return new BlockId(
        getDynamicPropertiesStore().getLatestBlockHeaderHash(),
        getDynamicPropertiesStore().getLatestBlockHeaderNumber());
  }

  public long getHeadBlockNum() {
    return getDynamicPropertiesStore().getLatestBlockHeaderNumber();
  }

  public long getHeadBlockTimeStamp() {
    return getDynamicPropertiesStore().getLatestBlockHeaderTimestamp();
  }


  public void clearAndWriteNeighbours(Set<Node> nodes) {
    this.peersStore.put("neighbours".getBytes(), nodes);
  }

  public Set<Node> readNeighbours() {
    return this.peersStore.get("neighbours".getBytes());
  }

  /**
   * Cycle thread to repush Transactions
   */
  private Runnable repushLoop =
      () -> {
        while (isRunRepushThread) {
          TransactionCapsule tx = null;
          try {
            if (isGeneratingBlock()) {
              TimeUnit.MILLISECONDS.sleep(10L);
              continue;
            }
            tx = getRepushTransactions().peek();
            if (tx != null) {
              this.rePush(tx);
            } else {
              TimeUnit.MILLISECONDS.sleep(50L);
            }
          } catch (Exception ex) {
            logger.error("unknown exception happened in repush loop", ex);
          } catch (Throwable throwable) {
            logger.error("unknown throwable happened in repush loop", throwable);
          } finally {
            if (tx != null) {
              getRepushTransactions().remove(tx);
            }
          }
        }
      };

  private Runnable triggerCapsuleProcessLoop =
      () -> {
        while (isRunTriggerCapsuleProcessThread) {
          try {
            TriggerCapsule tiggerCapsule = triggerCapsuleQueue.poll(1, TimeUnit.SECONDS);
            if (tiggerCapsule != null) {
              tiggerCapsule.processTrigger();
            }
          } catch (InterruptedException ex) {
            logger.info(ex.getMessage());
            Thread.currentThread().interrupt();
          } catch (Exception ex) {
            logger.error("unknown exception happened in process capsule loop", ex);
          } catch (Throwable throwable) {
            logger.error("unknown throwable happened in process capsule loop", throwable);
          }
        }
      };

  public void stopRepushThread() {
    isRunRepushThread = false;
  }

  public void stopRepushTriggerThread() {
    isRunTriggerCapsuleProcessThread = false;
  }

  @PostConstruct
  public void init() {
    revokingStore.disable();
    revokingStore.check();
    this.setWitnessController(WitnessController.createInstance(this));
    this.setProposalController(ProposalController.createInstance(this));
    this.pendingTransactions = Collections.synchronizedList(Lists.newArrayList());
    this.repushTransactions = new LinkedBlockingQueue<>();
    this.triggerCapsuleQueue = new LinkedBlockingQueue<>();

    this.initGenesis();
    try {
      this.khaosDb.start(getBlockById(getDynamicPropertiesStore().getLatestBlockHeaderHash()));
    } catch (ItemNotFoundException e) {
      logger.error(
          "Can not find Dynamic highest block from DB! \nnumber={} \nhash={}",
          getDynamicPropertiesStore().getLatestBlockHeaderNumber(),
          getDynamicPropertiesStore().getLatestBlockHeaderHash());
      logger.error(
          "Please delete database directory({}) and restart",
          Args.getInstance().getOutputDirectory());
      System.exit(1);
    } catch (BadItemException e) {
      e.printStackTrace();
      logger.error("DB data broken!");
      logger.error(
          "Please delete database directory({}) and restart",
          Args.getInstance().getOutputDirectory());
      System.exit(1);
    }
    forkController.init(this);

    if (Args.getInstance().isNeedToUpdateAsset() && needToUpdateAsset()) {
      new AssetUpdateHelper(this).doWork();
    }
    initCacheTxs();
    revokingStore.enable();
    validateSignService = Executors
        .newFixedThreadPool(Args.getInstance().getValidateSignThreadNum());
    Thread repushThread = new Thread(repushLoop);
    repushThread.start();

    // add contract event listener for subscribing
    if (Args.getInstance().isEventSubscribe()) {
      startEventSubscribing();
      Thread triggerCapsuleProcessThread = new Thread(triggerCapsuleProcessLoop);
      triggerCapsuleProcessThread.start();
    }
  }

  public BlockId getGenesisBlockId() {
    return this.genesisBlock.getBlockId();
  }

  public BlockCapsule getGenesisBlock() {
    return genesisBlock;
  }

  /**
   * init genesis block.
   */
  public void initGenesis() {
    this.genesisBlock = BlockUtil.newGenesisBlockCapsule();
    if (this.containBlock(this.genesisBlock.getBlockId())) {
      Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());
    } else {
      if (this.hasBlocks()) {
        logger.error(
            "genesis block modify, please delete database directory({}) and restart",
            Args.getInstance().getOutputDirectory());
        System.exit(1);
      } else {
        logger.info("create genesis block");
        Args.getInstance().setChainId(this.genesisBlock.getBlockId().toString());

        //保存进blockStore数据库中
        blockStore.put(this.genesisBlock.getBlockId().getBytes(), this.genesisBlock);
        this.blockIndexStore.put(this.genesisBlock.getBlockId());  //保存区块索引
        logger.info("save block: " + this.genesisBlock);
        
        // init DynamicPropertiesStore
        this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(0);
        this.dynamicPropertiesStore.saveLatestBlockHeaderHash( this.genesisBlock.getBlockId().getByteString());
        this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(this.genesisBlock.getTimeStamp());
        
        this.initAccount();
        this.initWitness();
        this.witnessController.initWits();
        this.khaosDb.start(genesisBlock);
        this.updateRecentBlock(genesisBlock);
      }
    }
  }

  /**  把创世区块指定的账户保存到accountStore数据库中
   * save account into database.
   */
  public void initAccount() {
    final Args args = Args.getInstance();
    final GenesisBlock genesisBlockArg = args.getGenesisBlock();
    genesisBlockArg
        .getAssets()
        .forEach(
            account -> {
              account.setAccountType("Normal"); // to be set in conf
              final AccountCapsule accountCapsule =
                  new AccountCapsule(
                      account.getAccountName(),
                      ByteString.copyFrom(account.getAddress()),
                      account.getAccountType(),
                      account.getBalance());
              this.accountStore.put(account.getAddress(), accountCapsule);
              this.accountIdIndexStore.put(accountCapsule);
              this.accountIndexStore.put(accountCapsule);
            });
  }

  /**   把创世区块指定的超级代表账户地址保存到数据库中
   * save witnesses into database.
   */
  private void initWitness() {
    final Args args = Args.getInstance();
    final GenesisBlock genesisBlockArg = args.getGenesisBlock();
    genesisBlockArg
        .getWitnesses()
        .forEach(
            key -> {
              byte[] keyAddress = key.getAddress();
              ByteString address = ByteString.copyFrom(keyAddress);

              final AccountCapsule accountCapsule;
              if (!this.accountStore.has(keyAddress)) {
                accountCapsule = new AccountCapsule(ByteString.EMPTY,
                    address, AccountType.AssetIssue, 0L);
              } else {
                accountCapsule = this.accountStore.getUnchecked(keyAddress);
              }
              accountCapsule.setIsWitness(true);
              this.accountStore.put(keyAddress, accountCapsule);

              final WitnessCapsule witnessCapsule =
                  new WitnessCapsule(address, key.getVoteCount(), key.getUrl());
              witnessCapsule.setIsJobs(true);
              this.witnessStore.put(keyAddress, witnessCapsule);
            });
  }

  public void initCacheTxs() {
    logger.info("begin to init txs cache.");
    int dbVersion = Args.getInstance().getStorage().getDbVersion();
    if (dbVersion != 2) {
      return;
    }
    long start = System.currentTimeMillis();
    long headNum = dynamicPropertiesStore.getLatestBlockHeaderNumber();
    long recentBlockCount = recentBlockStore.size();
    ListeningExecutorService service = MoreExecutors
        .listeningDecorator(Executors.newFixedThreadPool(50));
    List<ListenableFuture<?>> futures = new ArrayList<>();
    AtomicLong blockCount = new AtomicLong(0);
    AtomicLong emptyBlockCount = new AtomicLong(0);
    LongStream.rangeClosed(headNum - recentBlockCount + 1, headNum).forEach(
        blockNum -> futures.add(service.submit(() -> {
          try {
            blockCount.incrementAndGet();
            BlockCapsule blockCapsule = getBlockByNum(blockNum);
            if (blockCapsule.getTransactions().isEmpty()) {
              emptyBlockCount.incrementAndGet();
            }
            blockCapsule.getTransactions().stream()
                .map(tc -> tc.getTransactionId().getBytes())
                .map(bytes -> Maps.immutableEntry(bytes, Longs.toByteArray(blockNum)))
                .forEach(e -> transactionCache.put(e.getKey(), new BytesCapsule(e.getValue())));
          } catch (ItemNotFoundException | BadItemException e) {
            logger.info("init txs cache error.");
            throw new IllegalStateException("init txs cache error.");
          }
        })));
    ListenableFuture<?> future = Futures.allAsList(futures);
    try {
      future.get();
      service.shutdown();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (ExecutionException e) {
      logger.info(e.getMessage());
    }
    logger.info("end to init txs cache. trxids:{}, block count:{}, empty block count:{}, cost:{}",
        transactionCache.size(),
        blockCount.get(),
        emptyBlockCount.get(),
        System.currentTimeMillis() - start
    );
  }

  public AccountStore getAccountStore() {
    return this.accountStore;
  }

  public void adjustBalance(byte[] accountAddress, long amount)
      throws BalanceInsufficientException {
    AccountCapsule account = getAccountStore().getUnchecked(accountAddress);
    adjustBalance(account, amount);
  }

  /**
   * judge balance.
   */
  public void adjustBalance(AccountCapsule account, long amount)
      throws BalanceInsufficientException {

    long balance = account.getBalance();
    if (amount == 0) {
      return;
    }

    if (amount < 0 && balance < -amount) {
      throw new BalanceInsufficientException(
          StringUtil.createReadableString(account.createDbKey()) + " insufficient balance");
    }
    account.setBalance(Math.addExact(balance, amount));
    this.getAccountStore().put(account.getAddress().toByteArray(), account);
  }

  //超级代表产生区块，获得奖励，累计到代表账户中
  public void adjustAllowance(byte[] accountAddress, long amount)
      throws BalanceInsufficientException {
    AccountCapsule account = getAccountStore().getUnchecked(accountAddress);
    long allowance = account.getAllowance();
    if (amount == 0) {
      return;
    }

    if (amount < 0 && allowance < -amount) {
      throw new BalanceInsufficientException(
          StringUtil.createReadableString(accountAddress) + " insufficient balance");
    }
    account.setAllowance(allowance + amount);
    this.getAccountStore().put(account.createDbKey(), account);
  }

  //验证合约：   根据指定的区块hash、区块数据，核对与本机中的区块hash是否一致。
  void validateTapos(TransactionCapsule transactionCapsule) throws TaposException {
	//获取交易中的ref_block_hash和ref_block_bytes  
    byte[] refBlockHash = transactionCapsule.getInstance()
        .getRawData().getRefBlockHash().toByteArray();
    byte[] refBlockNumBytes = transactionCapsule.getInstance()
        .getRawData().getRefBlockBytes().toByteArray();  //就是区块ID
    try {
      //根据交易中的区块ID从链上获取区块的hash
      byte[] blockHash = this.recentBlockStore.get(refBlockNumBytes).getData();
      if (!Arrays.equals(blockHash, refBlockHash)) {
    	//如果链上对应的区块hash和交易中的hash不一致，抛出异常
        String str = String.format(
            "Tapos failed, different block hash, %s, %s , recent block %s, solid block %s head block %s",
            ByteArray.toLong(refBlockNumBytes), Hex.toHexString(refBlockHash),
            Hex.toHexString(blockHash),
            getSolidBlockId().getString(), getHeadBlockId().getString()).toString();
        logger.info(str);
        throw new TaposException(str);
      }
    } catch (ItemNotFoundException e) {  
    	//交易中指定的区块id链上获取不到， 抛出 TaposException异常
      String str = String.
          format("Tapos failed, block not found, ref block %s, %s , solid block %s head block %s",
              ByteArray.toLong(refBlockNumBytes), Hex.toHexString(refBlockHash),
              getSolidBlockId().getString(), getHeadBlockId().getString()).toString();
      logger.info(str);
      throw new TaposException(str);
    }
  }

  //检查交易的长度512K、过期时限是24小时
  /*tron中规定交易的大小不能超过500k,否则就会抛出TooBigTransactionException异常。
   * 创建交易时会给交易设定一个expiration参数，表示的是，这笔交易必须在这个时间内完成， 否则，就抛弃。
   * 如果用户是调用创建交易的接口自动创建， 节点会使用当前的头区块的区块时间 加上60s之后设置给expiration。
   * 当然，这个60s可以在节点启动配置文件中配置，最大不能超过24小时。
   */
  void validateCommon(TransactionCapsule transactionCapsule)
      throws TransactionExpirationException, TooBigTransactionException {
    if (transactionCapsule.getData().length > Constant.TRANSACTION_MAX_BYTE_SIZE) {
      throw new TooBigTransactionException(
          "too big transaction, the size is " + transactionCapsule.getData().length + " bytes");
    }
    
    long transactionExpiration = transactionCapsule.getExpiration();
    long headBlockTime = getHeadBlockTimeStamp();
    //交易时间不能超过规定期限
    if (transactionExpiration <= headBlockTime ||
        transactionExpiration > headBlockTime + Constant.MAXIMUM_TIME_UNTIL_EXPIRATION) {
      throw new TransactionExpirationException(
          "transaction expiration, transaction expiration time is " + transactionExpiration
              + ", but headBlockTime is " + headBlockTime);
    }
  }

  //禁止交易重现出现2次
  /*fullnode节点维护一个transactionCache，当收到一笔交易，执行后没有错误，就会将这笔交易放到transactionCache。
   * 并且每次收到新的交易，执行之前都会检查transactionCache中是否能够获取到，如果存在，就说明之前已经收到过了，
   * 抛出DupTransactionException异常，不会继续往下走广播的流程了，这样可以有效的降低广播风暴。
   * 
   * 但是这个逻辑也会给用户带来一个不方便的地方， 比如用户发送一笔交易到fullnode节点，fullnode节点检查并执行交易， 没有出现致命问题，
   * 但是合约因为能量不够导致执行失败的， 这样的交易也能够被广播。当用户从链上查到这笔交易，发现执行没有成功，于是抵押trx获得了足够的能量，
   * 再次将原有的交易发送了一遍，如果没有发生超时的情况， 一定会报DupTransactionException异常， 
   * 所以这种情况，用户应该将交易稍微修改一下，再次发送， 而不是原来的交易原封不动发送出去。
   * */
  void validateDup(TransactionCapsule transactionCapsule) throws DupTransactionException {
    if (containsTransaction(transactionCapsule)) {
      logger.debug(ByteArray.toHexString(transactionCapsule.getTransactionId().getBytes()));
      throw new DupTransactionException("dup trans");
    }
  }

  //先查缓冲区，再查数据库中是否存在指定交易
  private boolean containsTransaction(TransactionCapsule transactionCapsule) {
    if (transactionCache != null) {
      return transactionCache.has(transactionCapsule.getTransactionId().getBytes());
    }

    return transactionStore.has(transactionCapsule.getTransactionId().getBytes());
  }

  /**
   * push transaction into pending.
   * 调用processTransaction执行交易， 执行成功后将交易加到pending列表。
   */
  public boolean pushTransaction(final TransactionCapsule trx)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      AccountResourceInsufficientException, DupTransactionException, TaposException,
      TooBigTransactionException, TransactionExpirationException,
      ReceiptCheckErrException, VMIllegalException, TooBigTransactionResultException {

	//锁定push列表  
    synchronized (pushTransactionQueue) {
      pushTransactionQueue.add(trx);
    }

    try {
      if (!trx.validateSignature(this)) {  //如果签名错误抛出异常
        throw new ValidateSignatureException("trans sig validate failed");
      }

      synchronized (this) {
        if (!session.valid()) {
          session.setValue(revokingStore.buildSession());
        }
        
        //处理检查交易，检查通过后将交易放入pending列表
        try (ISession tmpSession = revokingStore.buildSession()) {
          processTransaction(trx, null);    //task1:执行交易
          pendingTransactions.add(trx);     //task2:执行成功后将交易放入pending列表
          tmpSession.merge();
        }
      }
    } finally {
      pushTransactionQueue.remove(trx);
    }
    return true;
  }

  public void consumeMultiSignFee(TransactionCapsule trx, TransactionTrace trace)
      throws AccountResourceInsufficientException {
    if (trx.getInstance().getSignatureCount() > 1) {
      long fee = getDynamicPropertiesStore().getMultiSignFee();

      List<Contract> contracts = trx.getInstance().getRawData().getContractList();
      for (Contract contract : contracts) {
        byte[] address = TransactionCapsule.getOwner(contract);
        AccountCapsule accountCapsule = getAccountStore().get(address);
        try {
          adjustBalance(accountCapsule, -fee);
          adjustBalance(this.getAccountStore().getBlackhole().createDbKey(), +fee);
        } catch (BalanceInsufficientException e) {
          throw new AccountResourceInsufficientException(
              "Account Insufficient  balance[" + fee + "] to MultiSign");
        }
      }

      trace.getReceipt().setMultiSignFee(fee);
    }
  }

  public void consumeBandwidth(TransactionCapsule trx, TransactionTrace trace)
      throws ContractValidateException, AccountResourceInsufficientException, TooBigTransactionResultException {
    BandwidthProcessor processor = new BandwidthProcessor(this);
    processor.consume(trx, trace);
  }


  /**
   * when switch fork need erase blocks on fork branch.
   */
  public synchronized void eraseBlock() {
    session.reset();
    try {
      BlockCapsule oldHeadBlock = getBlockById(
          getDynamicPropertiesStore().getLatestBlockHeaderHash());
      logger.info("begin to erase block:" + oldHeadBlock);
      khaosDb.pop();
      revokingStore.fastPop();
      logger.info("end to erase block:" + oldHeadBlock);
      popedTransactions.addAll(oldHeadBlock.getTransactions());

    } catch (ItemNotFoundException | BadItemException e) {
      logger.warn(e.getMessage(), e);
    }
  }

  public void pushVerifiedBlock(BlockCapsule block) throws ContractValidateException,
      ContractExeException, ValidateSignatureException, AccountResourceInsufficientException,
      TransactionExpirationException, TooBigTransactionException, DupTransactionException,
      TaposException, ValidateScheduleException, ReceiptCheckErrException,
      VMIllegalException, TooBigTransactionResultException, UnLinkedBlockException,
      NonCommonBlockException, BadNumberBlockException, BadBlockException {
	  //为什么要设置为true？ 超级代表生产区块才设置为true
    block.generatedByMyself = true;
    long start = System.currentTimeMillis();
    pushBlock(block);
    logger.info("push block cost:{}ms, blockNum:{}, blockHash:{}, trx count:{}",
        System.currentTimeMillis() - start,
        block.getNum(),
        block.getBlockId(),
        block.getTransactions().size());
  }

  private void applyBlock(BlockCapsule block) throws ContractValidateException,
      ContractExeException, ValidateSignatureException, AccountResourceInsufficientException,
      TransactionExpirationException, TooBigTransactionException, DupTransactionException,
      TaposException, ValidateScheduleException, ReceiptCheckErrException,
      VMIllegalException, TooBigTransactionResultException {
	
	  //
    processBlock(block);
    
    this.blockStore.put(block.getBlockId().getBytes(), block);  //保存区块入数据库
    this.blockIndexStore.put(block.getBlockId()); //保存区块id
    updateFork(block);
    if (System.currentTimeMillis() - block.getTimeStamp() >= 60_000) {
      revokingStore.setMaxFlushCount(SnapshotManager.DEFAULT_MAX_FLUSH_COUNT);
    } else {
      revokingStore.setMaxFlushCount(SnapshotManager.DEFAULT_MIN_FLUSH_COUNT);
    }
  }
  //切换分支
  private void switchFork(BlockCapsule newHead)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      ValidateScheduleException, AccountResourceInsufficientException, TaposException,
      TooBigTransactionException, TooBigTransactionResultException, DupTransactionException, TransactionExpirationException,
      NonCommonBlockException, ReceiptCheckErrException,
      VMIllegalException {
	  
    Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> binaryTree;
    try {
      binaryTree =
          khaosDb.getBranch(
              newHead.getBlockId(), getDynamicPropertiesStore().getLatestBlockHeaderHash());
    } catch (NonCommonBlockException e) {
      logger.info(
          "there is not the most recent common ancestor, need to remove all blocks in the fork chain.");
      BlockCapsule tmp = newHead;
      while (tmp != null) {
        khaosDb.removeBlk(tmp.getBlockId());
        tmp = khaosDb.getBlock(tmp.getParentHash());
      }

      throw e;
    }

    if (CollectionUtils.isNotEmpty(binaryTree.getValue())) {
      while (!getDynamicPropertiesStore()
          .getLatestBlockHeaderHash()
          .equals(binaryTree.getValue().peekLast().getParentHash())) {
        reorgContractTrigger();
        eraseBlock();
      }
    }

    if (CollectionUtils.isNotEmpty(binaryTree.getKey())) {
      List<KhaosBlock> first = new ArrayList<>(binaryTree.getKey());
      Collections.reverse(first);
      for (KhaosBlock item : first) {
        Exception exception = null;
        // todo  process the exception carefully later
        try (ISession tmpSession = revokingStore.buildSession()) {
          applyBlock(item.getBlk());
          tmpSession.commit();
        } catch (AccountResourceInsufficientException
            | ValidateSignatureException
            | ContractValidateException
            | ContractExeException
            | TaposException
            | DupTransactionException
            | TransactionExpirationException
            | ReceiptCheckErrException
            | TooBigTransactionException
            | TooBigTransactionResultException
            | ValidateScheduleException
            | VMIllegalException e) {
          logger.warn(e.getMessage(), e);
          exception = e;
          throw e;
        } finally {
          if (exception != null) {
            logger.warn("switch back because exception thrown while switching forks. " + exception
                    .getMessage(),
                exception);
            first.forEach(khaosBlock -> khaosDb.removeBlk(khaosBlock.getBlk().getBlockId()));
            khaosDb.setHead(binaryTree.getValue().peekFirst());

            while (!getDynamicPropertiesStore()
                .getLatestBlockHeaderHash()
                .equals(binaryTree.getValue().peekLast().getParentHash())) {
              eraseBlock();
            }

            List<KhaosBlock> second = new ArrayList<>(binaryTree.getValue());
            Collections.reverse(second);
            for (KhaosBlock khaosBlock : second) {
              // todo  process the exception carefully later
              try (ISession tmpSession = revokingStore.buildSession()) {
                applyBlock(khaosBlock.getBlk());
                tmpSession.commit();
              } catch (AccountResourceInsufficientException
                  | ValidateSignatureException
                  | ContractValidateException
                  | ContractExeException
                  | TaposException
                  | DupTransactionException
                  | TransactionExpirationException
                  | TooBigTransactionException
                  | ValidateScheduleException e) {
                logger.warn(e.getMessage(), e);
              }
            }
          }
        }
      }
    }
  }

  /**
   * save a block.
   */
  public synchronized void pushBlock(final BlockCapsule block)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      UnLinkedBlockException, ValidateScheduleException, AccountResourceInsufficientException,
      TaposException, TooBigTransactionException, TooBigTransactionResultException, DupTransactionException, TransactionExpirationException,
      BadNumberBlockException, BadBlockException, NonCommonBlockException,
      ReceiptCheckErrException, VMIllegalException {
    long start = System.currentTimeMillis();
    try (PendingManager pm = new PendingManager(this)) {

      //不是自己产生的区块，重新检验签名和默克尔树hash值。 
      if (!block.generatedByMyself) {
        if (!block.validateSignature(this)) {
          logger.warn("The signature is not validated.");
          throw new BadBlockException("The signature is not validated");
        }

        if (!block.calcMerkleRoot().equals(block.getMerkleRoot())) {
          logger.warn(
              "The merkle root doesn't match, Calc result is "
                  + block.calcMerkleRoot()
                  + " , the headers is "
                  + block.getMerkleRoot());
          throw new BadBlockException("The merkle hash is not validated");
        }
      }
      //检测双重代表节点？？？？？ 含义不明
      if (witnessService != null) {
        witnessService.checkDupWitness(block); //保存区块时一定会执行的
      }

      BlockCapsule newBlock = this.khaosDb.push(block);

      // DB don't need lower block，新区块的编号要正确
      if (getDynamicPropertiesStore().getLatestBlockHeaderHash() == null) {
        if (newBlock.getNum() != 0) {
          return;
        }
      } else {
        if (newBlock.getNum() <= getDynamicPropertiesStore().getLatestBlockHeaderNumber()) {
          return;
        }

        // switch fork，新区块的父块hash不是最新区块hash，表示出现了不同的区块链分支
        if (!newBlock
            .getParentHash()
            .equals(getDynamicPropertiesStore().getLatestBlockHeaderHash())) {
          logger.warn(
              "switch fork! new head num = {}, blockid = {}",
              newBlock.getNum(),
              newBlock.getBlockId());

          logger.warn(
              "******** before switchFork ******* push block: "
                  + block.toString()
                  + ", new block:"
                  + newBlock.toString()
                  + ", dynamic head num: "
                  + dynamicPropertiesStore.getLatestBlockHeaderNumber()
                  + ", dynamic head hash: "
                  + dynamicPropertiesStore.getLatestBlockHeaderHash()
                  + ", dynamic head timestamp: "
                  + dynamicPropertiesStore.getLatestBlockHeaderTimestamp()
                  + ", khaosDb head: "
                  + khaosDb.getHead()
                  + ", khaosDb miniStore size: "
                  + khaosDb.getMiniStore().size()
                  + ", khaosDb unlinkMiniStore size: "
                  + khaosDb.getMiniUnlinkedStore().size());

          switchFork(newBlock);
          logger.info("save block: " + newBlock);

          logger.warn(
              "******** after switchFork ******* push block: "
                  + block.toString()
                  + ", new block:"
                  + newBlock.toString()
                  + ", dynamic head num: "
                  + dynamicPropertiesStore.getLatestBlockHeaderNumber()
                  + ", dynamic head hash: "
                  + dynamicPropertiesStore.getLatestBlockHeaderHash()
                  + ", dynamic head timestamp: "
                  + dynamicPropertiesStore.getLatestBlockHeaderTimestamp()
                  + ", khaosDb head: "
                  + khaosDb.getHead()
                  + ", khaosDb miniStore size: "
                  + khaosDb.getMiniStore().size()
                  + ", khaosDb unlinkMiniStore size: "
                  + khaosDb.getMiniUnlinkedStore().size());

          return;
        }
        try (ISession tmpSession = revokingStore.buildSession()) {

          applyBlock(newBlock);
          tmpSession.commit();
          // if event subscribe is enabled, post block trigger to queue
          postBlockTrigger(newBlock);
        } catch (Throwable throwable) {
          logger.error(throwable.getMessage(), throwable);
          khaosDb.removeBlk(block.getBlockId());
          throw throwable;
        }
      }
      logger.info("save block: " + newBlock);
    }
    //clear ownerAddressSet 更新ownerAddressSet，清空并重新添加
    synchronized (pushTransactionQueue) {
      if (CollectionUtils.isNotEmpty(ownerAddressSet)) {
        Set<String> result = new HashSet<>();
        for (TransactionCapsule transactionCapsule : repushTransactions) {
          filterOwnerAddress(transactionCapsule, result);
        }
        for (TransactionCapsule transactionCapsule : pushTransactionQueue) {
          filterOwnerAddress(transactionCapsule, result);
        }
        ownerAddressSet.clear();
        ownerAddressSet.addAll(result);
      }
    }
    logger.info("pushBlock block number:{}, cost/txs:{}/{}",
        block.getNum(),
        System.currentTimeMillis() - start,
        block.getTransactions().size());
  }

  public void updateDynamicProperties(BlockCapsule block) {
    long slot = 1;
    if (block.getNum() != 1) {
      slot = witnessController.getSlotAtTime(block.getTimeStamp());
    }
    for (int i = 1; i < slot; ++i) {
      if (!witnessController.getScheduledWitness(i).equals(block.getWitnessAddress())) {
        WitnessCapsule w =
            this.witnessStore
                .getUnchecked(StringUtil.createDbKey(witnessController.getScheduledWitness(i)));
        w.setTotalMissed(w.getTotalMissed() + 1);
        this.witnessStore.put(w.createDbKey(), w);
        logger.info(
            "{} miss a block. totalMissed = {}", w.createReadableString(), w.getTotalMissed());
      }
      this.dynamicPropertiesStore.applyBlock(false);
    }
    this.dynamicPropertiesStore.applyBlock(true);

    if (slot <= 0) {
      logger.warn("missedBlocks [" + slot + "] is illegal");
    }

    logger.info("update head, num = {}", block.getNum());
    this.dynamicPropertiesStore.saveLatestBlockHeaderHash(block.getBlockId().getByteString());

    this.dynamicPropertiesStore.saveLatestBlockHeaderNumber(block.getNum());
    this.dynamicPropertiesStore.saveLatestBlockHeaderTimestamp(block.getTimeStamp());
    revokingStore.setMaxSize((int) (dynamicPropertiesStore.getLatestBlockHeaderNumber()
        - dynamicPropertiesStore.getLatestSolidifiedBlockNum()
        + 1));
    khaosDb.setMaxSize((int)
        (dynamicPropertiesStore.getLatestBlockHeaderNumber()
            - dynamicPropertiesStore.getLatestSolidifiedBlockNum()
            + 1));
  }

  /**
   * Get the fork branch.
   */
  public LinkedList<BlockId> getBlockChainHashesOnFork(final BlockId forkBlockHash)
      throws NonCommonBlockException {
    final Pair<LinkedList<KhaosBlock>, LinkedList<KhaosBlock>> branch =
        this.khaosDb.getBranch(
            getDynamicPropertiesStore().getLatestBlockHeaderHash(), forkBlockHash);

    LinkedList<KhaosBlock> blockCapsules = branch.getValue();

    if (blockCapsules.isEmpty()) {
      logger.info("empty branch {}", forkBlockHash);
      return Lists.newLinkedList();
    }

    LinkedList<BlockId> result = blockCapsules.stream()
        .map(KhaosBlock::getBlk)
        .map(BlockCapsule::getBlockId)
        .collect(Collectors.toCollection(LinkedList::new));

    result.add(blockCapsules.peekLast().getBlk().getParentBlockId());

    return result;
  }

  /**
   * judge id.
   *
   * @param blockHash blockHash
   */
  public boolean containBlock(final Sha256Hash blockHash) {
    try {
      return this.khaosDb.containBlockInMiniStore(blockHash)
          || blockStore.get(blockHash.getBytes()) != null;
    } catch (ItemNotFoundException | BadItemException e) {
      return false;
    }
  }

  public boolean containBlockInMainChain(BlockId blockId) {
    try {
      return blockStore.get(blockId.getBytes()) != null;
    } catch (ItemNotFoundException | BadItemException e) {
      return false;
    }
  }

  public void setBlockReference(TransactionCapsule trans) {
    byte[] headHash = getDynamicPropertiesStore().getLatestBlockHeaderHash().getBytes();
    long headNum = getDynamicPropertiesStore().getLatestBlockHeaderNumber();
    trans.setReference(headNum, headHash);
  }

  /**
   * Get a BlockCapsule by id.
   */
  public BlockCapsule getBlockById(final Sha256Hash hash)
      throws BadItemException, ItemNotFoundException {
    BlockCapsule block = this.khaosDb.getBlock(hash);
    if (block == null) {
      block = blockStore.get(hash.getBytes());
    }
    return block;
  }

  /**
   * judge has blocks.
   */
  public boolean hasBlocks() {
    return blockStore.iterator().hasNext() || this.khaosDb.hasData();
  }

  /**
   * Process transaction.
   * 是执行交易的入口， 在这个函数中会对交易进一步检查，检查完成后调用vm执行交易， 
   * 执行交易后结算执行产生的费用，最后将执行结果存储到数据库（TransactionInfo），
   * 另外如果节点配置的事件插件，还会触发相应的事件。
   */
  public boolean processTransaction(final TransactionCapsule trxCap, BlockCapsule blockCap)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      AccountResourceInsufficientException, TransactionExpirationException, TooBigTransactionException, TooBigTransactionResultException,
      DupTransactionException, TaposException, ReceiptCheckErrException, VMIllegalException {
    if (trxCap == null) {
      return false;
    }

    //----------------------------------------------------------------------
    //task1:执行交易前的必要检查
    // 1. tapos检查
    // 2. 检查交易大小是否超限、时间是否超期
    // 3. 检查交易中的contract数量
    // 4. 判断transactioncache中有没有重复交易
    // 5. 签名检查
    //----------------------------------------------------------------------
    // 1. tapos检查
    validateTapos(trxCap);  //验证合约：   根据指定的区块hash、区块数据，核对与本机中的区块hash是否一致。
    
    //2. 检查交易大小是否超限、时间是否超期
    validateCommon(trxCap); //检查交易的长度512K、过期时限是24小时

    //3. 检查交易中的contract数量。交易中的合约个数只能=1， 一个交易只能指定唯一的合约。
    if (trxCap.getInstance().getRawData().getContractList().size() != 1) {
      throw new ContractSizeNotEqualToOneException(
          "act size should be exactly 1, this is extend feature");
    }

    //4. 判断transactioncache中有没有重复交易
    validateDup(trxCap);  //禁止交易重现出现2次

    //5. 签名检查
    if (!trxCap.validateSignature(this)) {
      throw new ValidateSignatureException("trans sig validate failed");
    }

    //----------------------------------------------------------------------
    //task2:带宽费用和多重签名费用扣除
    //这里的带宽扣费是发生在交易执行前， 对一些确定性的支出进行扣费，比如根据用户的交易字节数扣除相应带宽等等，
    //如果是交易使用了多重签名，要扣除一定的费用。
    //----------------------------------------------------------------------
    //为交易设置一个trace,用于记录本次执行交易结果，以后要和区块中的结果进行比对。
    TransactionTrace trace = new TransactionTrace(trxCap, this);
    trxCap.setTrxTrace(trace);

    consumeBandwidth(trxCap, trace);    //消费带宽
    consumeMultiSignFee(trxCap, trace); //多重签名扣费 

    //----------------------------------------------------------------------
    //task3:evm初始化并执行交易
    //----------------------------------------------------------------------
    //初始化evm
    VMConfig.initVmHardFork();
    VMConfig.initAllowMultiSign(dynamicPropertiesStore.getAllowMultiSign());
    VMConfig.initAllowTvmTransferTrc10(dynamicPropertiesStore.getAllowTvmTransferTrc10());
    trace.init(blockCap, eventPluginLoaded);
    trace.checkIsConstant();
    trace.exec();    //执行交易

    //如果本次处理是因为接收到block的处理
    /*执行结束后，如果本次pushTransaction是在第2和第3种调用流程中， 还需要将交易的执行结果设置到收据的result中，
     *因为在这种情况下，相当于交易已经上链， 需要保存交易的结果，让用户可以查询。
     **/
    if (Objects.nonNull(blockCap)) {
      trace.setResult();    //保存交易执行结果
      
      /* 执行交易完成后还有一个比较特殊的处理，如果pushTransaction是在第2种流程
       * (节点收到其他节点广播的区块后对区块中的交易进行执行验证)中，并且交易需要重试，那么就会重新执行一遍交易。*/
      /* 这个判断的意思的是如果blockcap中有签名，就会进入分支执行。
  	   * 第3个流程是出块节点打包区块的流程， 也就是说必须执行完所有需要打包的交易后，才会进行签名， 那么执行交易的时候一定是没有签名的。
  	   * 所以能够进入分支执行的情况一定是第2种情况，即收到别人的广播过来的区块，进行验证操作。*/
      if (!blockCap.getInstance().getBlockHeader().getWitnessSignature().isEmpty()) {
    	
    	//什么情况下交易需要重试呢?
    	/* 如果交易中ContractRet的类型不为OUT_OF_TIME，但是本地执行的结果是OUT_OF_TIME， 就会被认为需要重试，
    	 * 也就是别的节点执行这个交易没有超时， 但是我执行超时，那么有可能是我这个节点机器的性能有波动导致，那么再重新执行一遍试试，
    	 * 关于如果避免节点验证交易超时可以参考：https://blog.csdn.net/AdminZYM/article/details/93253573
    	 * */  
        if (trace.checkNeedRetry()) {
          String txId = Hex.toHexString(trxCap.getTransactionId().getBytes());
          logger.info("Retry for tx id: {}", txId);
          trace.init(blockCap, eventPluginLoaded);
          trace.checkIsConstant();
          trace.exec();
          trace.setResult();
          logger.info("Retry result for tx id: {}, tx resultCode in receipt: {}",
              txId, trace.getReceipt().getResult());
        }
        //本地执行的结果和接收到的交易中的结果是不是一致， 如果不同向上抛出异常
        trace.check();
      }
    }

    //----------------------------------------------------------------------
    //task4:evm执行之后的energy费用结算
    //----------------------------------------------------------------------
    //支付费用
    trace.finalization();
    /* 还需要设置一下Transaction中的contractRet，这里的contractRet和之前trace.setResult设置的内容是一样的，
     * 只不过trace.setResult设置的是交易的收据中的result，交易的收据是存储在TransactionInfo数据结构中
     * trxCap.setResult设置的是交易中的contractRet， 保存在Transaction数据结构中。
     * ， 要注意的是tron中一笔交易在数据库中会存两种形式：
     * Transaction、TransactionInfo。  
     * Transaction：  保存的是交易的原始结构和执行结果， 通过gettransactionbyid获取
     * TransactionInfo：主要保存的是执行后的收据，    通过gettransactioninfobyid获取
     **/
    if (Objects.nonNull(blockCap) && getDynamicPropertiesStore().supportVM()) {
      trxCap.setResult(trace.getRuntime());
    }
    
    //----------------------------------------------------------------------
    //task5: 将执行结果存储到数据库并发送事件
    //----------------------------------------------------------------------
    //将transacton存储到数据库
    transactionStore.put(trxCap.getTransactionId().getBytes(), trxCap);
    Optional.ofNullable(transactionCache)
        .ifPresent(t -> t.put(trxCap.getTransactionId().getBytes(),
            new BytesCapsule(ByteArray.fromLong(trxCap.getBlockNum()))));
    
    //TransactionInfo存到数据库中
    TransactionInfoCapsule transactionInfo = TransactionInfoCapsule
        .buildInstance(trxCap, blockCap, trace);
    transactionHistoryStore.put(trxCap.getTransactionId().getBytes(), transactionInfo);

    // if event subscribe is enabled, post contract triggers to queue
    //如果节点配置了event插件，还会发送交易执行成功的事件到事件队列中。
    postContractTrigger(trace, false);
    //
    Contract contract = trxCap.getInstance().getRawData().getContract(0);
    if (isMultSignTransaction(trxCap.getInstance())) {
      ownerAddressSet.add(ByteArray.toHexString(TransactionCapsule.getOwner(contract)));
    }

    return true;
  }


  /**
   * Get the block id from the number.
   */
  public BlockId getBlockIdByNum(final long num) throws ItemNotFoundException {
    return this.blockIndexStore.get(num);
  }

  public BlockCapsule getBlockByNum(final long num) throws ItemNotFoundException, BadItemException {
    return getBlockById(getBlockIdByNum(num));
  }

  /**
   * Generate a block.
   */
  public synchronized BlockCapsule generateBlock(
      final WitnessCapsule witnessCapsule, final long when, final byte[] privateKey,
      Boolean lastHeadBlockIsMaintenanceBefore, Boolean needCheckWitnessPermission)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      UnLinkedBlockException, ValidateScheduleException, AccountResourceInsufficientException {

    //check that the first block after the maintenance period has just been processed
    // if (lastHeadBlockIsMaintenanceBefore != lastHeadBlockIsMaintenance()) {
    if (!witnessController.validateWitnessSchedule(witnessCapsule.getAddress(), when)) {
      logger.info("It's not my turn, "
          + "and the first block after the maintenance period has just been processed.");

      logger.info("when:{},lastHeadBlockIsMaintenanceBefore:{},lastHeadBlockIsMaintenanceAfter:{}",
          when, lastHeadBlockIsMaintenanceBefore, lastHeadBlockIsMaintenance());

      return null;
    }
    // }

    final long timestamp = this.dynamicPropertiesStore.getLatestBlockHeaderTimestamp();
    final long number = this.dynamicPropertiesStore.getLatestBlockHeaderNumber();
    final Sha256Hash preHash = this.dynamicPropertiesStore.getLatestBlockHeaderHash();

    // judge create block time
    if (when < timestamp) {
      throw new IllegalArgumentException("generate block timestamp is invalid.");
    }

    long postponedTrxCount = 0;

    final BlockCapsule blockCapsule = new BlockCapsule(number + 1, preHash,
    		                                when, witnessCapsule.getAddress());
    blockCapsule.generatedByMyself = true;
    session.reset();
    session.setValue(revokingStore.buildSession());
    
    if (needCheckWitnessPermission && !witnessService.
        validateWitnessPermission(witnessCapsule.getAddress())) {
      logger.warn("Witness permission is wrong");
      return null;
    }

    Set<String> accountSet = new HashSet<>();  //交易的所有者账户地址集合
    Iterator<TransactionCapsule> iterator = pendingTransactions.iterator();
    while (iterator.hasNext() || repushTransactions.size() > 0) {
      boolean fromPending = false;  //表示交易来源（pendingTransactions、repushTransactions），优先打包未决交易
      TransactionCapsule trx;
      if (iterator.hasNext()) {
        fromPending = true;
        trx = (TransactionCapsule) iterator.next();
      } else {
        trx = repushTransactions.poll();
      }

      //保证3秒钟内只有50%*50%的时间0.75s处理其他生产块， 其它时间用于广播事务，因此交易数量很多的情况下就不能包含那么多了交易了
      if (DateTime.now().getMillis() - when
          > ChainConstant.BLOCK_PRODUCED_INTERVAL * 0.5
          * Args.getInstance().getBlockProducedTimeOut()
          / 100) {
        logger.warn("Processing transaction time exceeds the 50% producing time銆�");
        break;
      }
      // check the block size 限制区块大小200万字节
      if ((blockCapsule.getInstance().getSerializedSize() + trx.getSerializedSize() + 3)
          > ChainConstant.BLOCK_SIZE) {
        postponedTrxCount++;
        continue;
      }
      //交易中的合约，
      Contract contract = trx.getInstance().getRawData().getContract(0);
      byte[] owner = TransactionCapsule.getOwner(contract);
      String ownerAddress = ByteArray.toHexString(owner);
      if (accountSet.contains(ownerAddress)) {
        continue;
      } else {
        if (isMultSignTransaction(trx.getInstance())) {
          accountSet.add(ownerAddress);
        }
      }
      if (ownerAddressSet.contains(ownerAddress)) {
        trx.setVerified(false);
      }
      // apply transaction
      try (ISession tmpSeesion = revokingStore.buildSession()) {
    	  
        processTransaction(trx, blockCapsule);   //这一步很长很难看
        
        tmpSeesion.merge();
        
        // 添加到block中， 同时添加到transactions列表中
        blockCapsule.addTransaction(trx);
        
        if (fromPending) {
          iterator.remove();   //添加完交易后就删除。
        }
      } catch (ContractExeException e) {
        logger.info("contract not processed during execute");
        logger.debug(e.getMessage(), e);
      } catch (ContractValidateException e) {
        logger.info("contract not processed during validate");
        logger.debug(e.getMessage(), e);
      } catch (TaposException e) {
        logger.info("contract not processed during TaposException");
        logger.debug(e.getMessage(), e);
      } catch (DupTransactionException e) {
        logger.info("contract not processed during DupTransactionException");
        logger.debug(e.getMessage(), e);
      } catch (TooBigTransactionException e) {
        logger.info("contract not processed during TooBigTransactionException");
        logger.debug(e.getMessage(), e);
      } catch (TooBigTransactionResultException e) {
        logger.info("contract not processed during TooBigTransactionResultException");
        logger.debug(e.getMessage(), e);
      } catch (TransactionExpirationException e) {
        logger.info("contract not processed during TransactionExpirationException");
        logger.debug(e.getMessage(), e);
      } catch (AccountResourceInsufficientException e) {
        logger.info("contract not processed during AccountResourceInsufficientException");
        logger.debug(e.getMessage(), e);
      } catch (ValidateSignatureException e) {
        logger.info("contract not processed during ValidateSignatureException");
        logger.debug(e.getMessage(), e);
      } catch (ReceiptCheckErrException e) {
        logger.info("OutOfSlotTime exception: {}", e.getMessage());
        logger.debug(e.getMessage(), e);
      } catch (VMIllegalException e) {
        logger.warn(e.getMessage(), e);
      }
    }

    session.reset();

    if (postponedTrxCount > 0) {
      logger.info("{} transactions over the block size limit", postponedTrxCount);
    }

    logger.info(
        "postponedTrxCount[" + postponedTrxCount + "],TrxLeft[" + pendingTransactions.size()
            + "],repushTrxCount[" + repushTransactions.size() + "]");
    blockCapsule.setMerkleRoot();   //设置默克尔树根
    blockCapsule.sign(privateKey);  //用私钥签名,对区块头数据计算签名

    try {
      this.pushBlock(blockCapsule);  //保存区块到数据库
      return blockCapsule;
    } catch (TaposException e) {
      logger.info("contract not processed during TaposException");
    } catch (TooBigTransactionException e) {
      logger.info("contract not processed during TooBigTransactionException");
    } catch (DupTransactionException e) {
      logger.info("contract not processed during DupTransactionException");
    } catch (TransactionExpirationException e) {
      logger.info("contract not processed during TransactionExpirationException");
    } catch (BadNumberBlockException e) {
      logger.info("generate block using wrong number");
    } catch (BadBlockException e) {
      logger.info("block exception");
    } catch (NonCommonBlockException e) {
      logger.info("non common exception");
    } catch (ReceiptCheckErrException e) {
      logger.info("OutOfSlotTime exception: {}", e.getMessage());
      logger.debug(e.getMessage(), e);
    } catch (VMIllegalException e) {
      logger.warn(e.getMessage(), e);
    } catch (TooBigTransactionResultException e) {
      logger.info("contract not processed during TooBigTransactionResultException");
    }

    return null;
  }

  private void filterOwnerAddress(TransactionCapsule transactionCapsule, Set<String> result) {
    Contract contract = transactionCapsule.getInstance().getRawData().getContract(0);
    byte[] owner = TransactionCapsule.getOwner(contract);
    String ownerAddress = ByteArray.toHexString(owner);
    if (ownerAddressSet.contains(ownerAddress)) {
      result.add(ownerAddress);
    }
  }

  private boolean isMultSignTransaction(Transaction transaction) {
    Contract contract = transaction.getRawData().getContract(0);
    switch (contract.getType()) {
      case AccountPermissionUpdateContract: {
        return true;
      }
      default:
    }
    return false;
  }

  public TransactionStore getTransactionStore() {
    return this.transactionStore;
  }


  public TransactionHistoryStore getTransactionHistoryStore() {
    return this.transactionHistoryStore;
  }

  public BlockStore getBlockStore() {
    return this.blockStore;
  }


  /**
   * process block.
   */
  public void processBlock(BlockCapsule block)
      throws ValidateSignatureException, ContractValidateException, ContractExeException,
      AccountResourceInsufficientException, TaposException, TooBigTransactionException,
      DupTransactionException, TransactionExpirationException, ValidateScheduleException,
      ReceiptCheckErrException, VMIllegalException, TooBigTransactionResultException {
    // todo set revoking db max size.

    // checkWitness
    if (!witnessController.validateWitnessSchedule(block)) {
      throw new ValidateScheduleException("validateWitnessSchedule error");
    }

    //reset BlockEnergyUsage
    this.dynamicPropertiesStore.saveBlockEnergyUsage(0);

    //parallel check sign
    if (!block.generatedByMyself) {
      try {
        preValidateTransactionSign(block);
      } catch (InterruptedException e) {
        logger.error("parallel check sign interrupted exception! block info: {}", block, e);
        Thread.currentThread().interrupt();
      }
    }

    for (TransactionCapsule transactionCapsule : block.getTransactions()) {
      transactionCapsule.setBlockNum(block.getNum()); //给每笔交易设置本区块编号
      if (block.generatedByMyself) {
        transactionCapsule.setVerified(true);
      }
      processTransaction(transactionCapsule, block);
    }

    boolean needMaint = needMaintenance(block.getTimeStamp());
    if (needMaint) {
      if (block.getNum() == 1) {  //创世区块编号是0， 这说明产生的是第一个区块，就设定了维护时间
        this.dynamicPropertiesStore.updateNextMaintenanceTime(block.getTimeStamp());
      } else {
        this.processMaintenance(block);
      }
    }
    
    //自适应能源控制，作用未知？？？？
    if (getDynamicPropertiesStore().getAllowAdaptiveEnergy() == 1) {
      EnergyProcessor energyProcessor = new EnergyProcessor(this);
      energyProcessor.updateTotalEnergyAverageUsage();
      energyProcessor.updateAdaptiveTotalEnergyLimit();
    }
    this.updateDynamicProperties(block);
    this.updateSignedWitness(block);     //更新超级代表关联的区块数据， 累加奖励
    this.updateLatestSolidifiedBlock();  //作用未知？？？
    this.updateTransHashCache(block);   //区块中的交易都写入缓存transactionIdCache
    updateMaintenanceState(needMaint);
    updateRecentBlock(block);
  }

  //区块中的交易都写入缓存transactionIdCache
  private void updateTransHashCache(BlockCapsule block) {
    for (TransactionCapsule transactionCapsule : block.getTransactions()) {
      this.transactionIdCache.put(transactionCapsule.getTransactionId(), true);
    }
  }

  public void updateRecentBlock(BlockCapsule block) {
    this.recentBlockStore.put(ByteArray.subArray(
        ByteArray.fromLong(block.getNum()), 6, 8),
        new BytesCapsule(ByteArray.subArray(block.getBlockId().getBytes(), 8, 16)));
  }

  /**
   * update the latest solidified block.
   */
  public void updateLatestSolidifiedBlock() {
    List<Long> numbers =
        witnessController
            .getActiveWitnesses()
            .stream()
            .map(address -> witnessController.getWitnesseByAddress(address).getLatestBlockNum())
            .sorted()
            .collect(Collectors.toList());

    long size = witnessController.getActiveWitnesses().size();
    int solidifiedPosition = (int) (size * (1 - SOLIDIFIED_THRESHOLD * 1.0 / 100));
    if (solidifiedPosition < 0) {
      logger.warn(
          "updateLatestSolidifiedBlock error, solidifiedPosition:{},wits.size:{}",
          solidifiedPosition,
          size);
      return;
    }
    long latestSolidifiedBlockNum = numbers.get(solidifiedPosition);
    //if current value is less than the previous value锛宬eep the previous value.
    if (latestSolidifiedBlockNum < getDynamicPropertiesStore().getLatestSolidifiedBlockNum()) {
      logger.warn("latestSolidifiedBlockNum = 0,LatestBlockNum:{}", numbers);
      return;
    }

    getDynamicPropertiesStore().saveLatestSolidifiedBlockNum(latestSolidifiedBlockNum);
    this.latestSolidifiedBlockNumber = latestSolidifiedBlockNum;
    logger.info("update solid block, num = {}", latestSolidifiedBlockNum);
  }

  //更新forkController
  public void updateFork(BlockCapsule block) {
    forkController.update(block);
  }

  public long getSyncBeginNumber() {
    logger.info("headNumber:" + dynamicPropertiesStore.getLatestBlockHeaderNumber());
    logger.info(
        "syncBeginNumber:"
            + (dynamicPropertiesStore.getLatestBlockHeaderNumber() - revokingStore.size()));
    logger.info("solidBlockNumber:" + dynamicPropertiesStore.getLatestSolidifiedBlockNum());
    return dynamicPropertiesStore.getLatestBlockHeaderNumber() - revokingStore.size();
  }

  public BlockId getSolidBlockId() {
    try {
      long num = dynamicPropertiesStore.getLatestSolidifiedBlockNum();
      return getBlockIdByNum(num);
    } catch (Exception e) {
      return getGenesisBlockId();
    }
  }

  /**
   * Determine if the current time is maintenance time.
   * 每生产一个区块就检查是否到了一轮维护时间，12小时间隔
   */
  public boolean needMaintenance(long blockTime) {
    return this.dynamicPropertiesStore.getNextMaintenanceTime() <= blockTime;
  }

  /**
   * Perform maintenance. 一轮选举时间到了，该进行维护处理了
   */
  private void processMaintenance(BlockCapsule block) {
    proposalController.processProposals();   //处理提案
    witnessController.updateWitness();       //更新见证人    
    this.dynamicPropertiesStore.updateNextMaintenanceTime(block.getTimeStamp()); //更新下一个维护时间点
    forkController.reset();    // ????
  }

  /**
   * @param block the block update signed witness. set witness who signed block the 1. the latest
   * block num 2. pay the trx to witness. 3. the latest slot num.
   */
  public void updateSignedWitness(BlockCapsule block) {
    // TODO: add verification  更新Unchecked中的witnessCapsule
    WitnessCapsule witnessCapsule =
        witnessStore.getUnchecked(
            block.getInstance().getBlockHeader().getRawData().getWitnessAddress().toByteArray());
    witnessCapsule.setTotalProduced(witnessCapsule.getTotalProduced() + 1);
    witnessCapsule.setLatestBlockNum(block.getNum());
    witnessCapsule.setLatestSlotNum(witnessController.getAbSlotAtTime(block.getTimeStamp()));

    // Update memory witness status， 更新数据库中的witnessCapsule
    WitnessCapsule wit = witnessController.getWitnesseByAddress(block.getWitnessAddress());
    if (wit != null) {
      wit.setTotalProduced(witnessCapsule.getTotalProduced() + 1);
      wit.setLatestBlockNum(block.getNum());
      wit.setLatestSlotNum(witnessController.getAbSlotAtTime(block.getTimeStamp()));
    }

    this.getWitnessStore().put(witnessCapsule.getAddress().toByteArray(), witnessCapsule);

    try {  //超级代表产生区块，获得奖励，累计到代表账户中
      adjustAllowance(witnessCapsule.getAddress().toByteArray(),
          getDynamicPropertiesStore().getWitnessPayPerBlock());
    } catch (BalanceInsufficientException e) {
      logger.warn(e.getMessage(), e);
    }

    logger.debug(
        "updateSignedWitness. witness address:{}, blockNum:{}, totalProduced:{}",
        witnessCapsule.createReadableString(),
        block.getNum(),
        witnessCapsule.getTotalProduced());
  }

  public void updateMaintenanceState(boolean needMaint) {
    if (needMaint) {
      getDynamicPropertiesStore().saveStateFlag(1);
    } else {
      getDynamicPropertiesStore().saveStateFlag(0);
    }
  }

  public boolean lastHeadBlockIsMaintenance() {
    return getDynamicPropertiesStore().getStateFlag() == 1;
  }

  // To be added
  public long getSkipSlotInMaintenance() {
    return getDynamicPropertiesStore().getMaintenanceSkipSlots();
  }

  public AssetIssueStore getAssetIssueStore() {
    return assetIssueStore;
  }

  public AssetIssueV2Store getAssetIssueV2Store() {
    return assetIssueV2Store;
  }

  public AssetIssueStore getAssetIssueStoreFinal() {
    if (getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      return getAssetIssueStore();
    } else {
      return getAssetIssueV2Store();
    }
  }

  public void setAssetIssueStore(AssetIssueStore assetIssueStore) {
    this.assetIssueStore = assetIssueStore;
  }

  public void setBlockIndexStore(BlockIndexStore indexStore) {
    this.blockIndexStore = indexStore;
  }

  public AccountIdIndexStore getAccountIdIndexStore() {
    return this.accountIdIndexStore;
  }

  public void setAccountIdIndexStore(AccountIdIndexStore indexStore) {
    this.accountIdIndexStore = indexStore;
  }

  public AccountIndexStore getAccountIndexStore() {
    return this.accountIndexStore;
  }

  public void setAccountIndexStore(AccountIndexStore indexStore) {
    this.accountIndexStore = indexStore;
  }

  public void closeAllStore() {
    logger.info("******** begin to close db ********");
    closeOneStore(accountStore);
    closeOneStore(blockStore);
    closeOneStore(blockIndexStore);
    closeOneStore(accountIdIndexStore);
    closeOneStore(accountIndexStore);
    closeOneStore(witnessStore);
    closeOneStore(witnessScheduleStore);
    closeOneStore(assetIssueStore);
    closeOneStore(dynamicPropertiesStore);
    closeOneStore(transactionStore);
    closeOneStore(codeStore);
    closeOneStore(contractStore);
    closeOneStore(storageRowStore);
    closeOneStore(exchangeStore);
    closeOneStore(peersStore);
    closeOneStore(proposalStore);
    closeOneStore(recentBlockStore);
    closeOneStore(transactionHistoryStore);
    closeOneStore(votesStore);
    closeOneStore(delegatedResourceStore);
    closeOneStore(delegatedResourceAccountIndexStore);
    closeOneStore(assetIssueV2Store);
    closeOneStore(exchangeV2Store);
    logger.info("******** end to close db ********");
  }

  public void closeOneStore(ITronChainBase database) {
    logger.info("******** begin to close " + database.getName() + " ********");
    try {
      database.close();
    } catch (Exception e) {
      logger.info("failed to close  " + database.getName() + ". " + e);
    } finally {
      logger.info("******** end to close " + database.getName() + " ********");
    }
  }

  public boolean isTooManyPending() {
    return getPendingTransactions().size() + getRepushTransactions().size()
        > MAX_TRANSACTION_PENDING;
  }

  public boolean isGeneratingBlock() {
    if (Args.getInstance().isWitness()) {
      return witnessController.isGeneratingBlock();
    }
    return false;
  }

  private static class ValidateSignTask implements Callable<Boolean> {

    private TransactionCapsule trx;
    private CountDownLatch countDownLatch;
    private Manager manager;

    ValidateSignTask(TransactionCapsule trx, CountDownLatch countDownLatch,
        Manager manager) {
      this.trx = trx;
      this.countDownLatch = countDownLatch;
      this.manager = manager;
    }

    @Override
    public Boolean call() throws ValidateSignatureException {
      try {
        trx.validateSignature(manager);
      } catch (ValidateSignatureException e) {
        throw e;
      } finally {
        countDownLatch.countDown();
      }
      return true;
    }
  }

  public void preValidateTransactionSign(BlockCapsule block)
      throws InterruptedException, ValidateSignatureException {
    logger.info("PreValidate Transaction Sign, size:" + block.getTransactions().size()
        + ",block num:" + block.getNum());
    int transSize = block.getTransactions().size();
    if (transSize <= 0) {
      return;
    }
    CountDownLatch countDownLatch = new CountDownLatch(transSize);
    List<Future<Boolean>> futures = new ArrayList<>(transSize);

    for (TransactionCapsule transaction : block.getTransactions()) {
      Future<Boolean> future = validateSignService
          .submit(new ValidateSignTask(transaction, countDownLatch, this));
      futures.add(future);
    }
    countDownLatch.await();

    for (Future<Boolean> future : futures) {
      try {
        future.get();
      } catch (ExecutionException e) {
        throw new ValidateSignatureException(e.getCause().getMessage());
      }
    }
  }

  public void rePush(TransactionCapsule tx) {
    if (containsTransaction(tx)) {
      return;
    }

    try {
      this.pushTransaction(tx);
    } catch (ValidateSignatureException | ContractValidateException | ContractExeException | AccountResourceInsufficientException | VMIllegalException e) {
      logger.debug(e.getMessage(), e);
    } catch (DupTransactionException e) {
      logger.debug("pending manager: dup trans", e);
    } catch (TaposException e) {
      logger.debug("pending manager: tapos exception", e);
    } catch (TooBigTransactionException e) {
      logger.debug("too big transaction");
    } catch (TransactionExpirationException e) {
      logger.debug("expiration transaction");
    } catch (ReceiptCheckErrException e) {
      logger.debug("outOfSlotTime transaction");
    } catch (TooBigTransactionResultException e) {
      logger.debug("too big transaction result");
    }
  }

  public void setMode(boolean mode) {
    revokingStore.setMode(mode);
  }

  private void startEventSubscribing() {

    try {
      eventPluginLoaded = EventPluginLoader.getInstance()
          .start(Args.getInstance().getEventPluginConfig());

      if (!eventPluginLoaded) {
        logger.error("failed to load eventPlugin");
      }

      FilterQuery eventFilter = Args.getInstance().getEventFilter();
      if (!Objects.isNull(eventFilter)) {
        EventPluginLoader.getInstance().setFilterQuery(eventFilter);
      }

    } catch (Exception e) {
      logger.error("{}", e);
    }
  }

  private void postBlockTrigger(final BlockCapsule newBlock) {
    if (eventPluginLoaded && EventPluginLoader.getInstance().isBlockLogTriggerEnable()) {
      BlockLogTriggerCapsule blockLogTriggerCapsule = new BlockLogTriggerCapsule(newBlock);
      blockLogTriggerCapsule.setLatestSolidifiedBlockNumber(latestSolidifiedBlockNumber);
      boolean result = triggerCapsuleQueue.offer(blockLogTriggerCapsule);
      if (!result) {
        logger.info("too many trigger, lost block trigger: {}", newBlock.getBlockId());
      }
    }

    for (TransactionCapsule e : newBlock.getTransactions()) {
      postTransactionTrigger(e, newBlock);
    }
  }

  private void postTransactionTrigger(final TransactionCapsule trxCap,
      final BlockCapsule blockCap) {
    if (eventPluginLoaded && EventPluginLoader.getInstance().isTransactionLogTriggerEnable()) {
      TransactionLogTriggerCapsule trx = new TransactionLogTriggerCapsule(trxCap, blockCap);
      trx.setLatestSolidifiedBlockNumber(latestSolidifiedBlockNumber);
      boolean result = triggerCapsuleQueue.offer(trx);
      if (!result) {
        logger.info("too many trigger, lost transaction trigger: {}", trxCap.getTransactionId());
      }
    }
  }

  private void reorgContractTrigger() {
    if (eventPluginLoaded &&
        (EventPluginLoader.getInstance().isContractEventTriggerEnable()
            || EventPluginLoader.getInstance().isContractLogTriggerEnable())) {
      logger.info("switchfork occured, post reorgContractTrigger");
      try {
        BlockCapsule oldHeadBlock = getBlockById(
            getDynamicPropertiesStore().getLatestBlockHeaderHash());
        for (TransactionCapsule trx : oldHeadBlock.getTransactions()) {
          postContractTrigger(trx.getTrxTrace(), true);
        }
      } catch (BadItemException | ItemNotFoundException e) {
        logger.error("block header hash not exists or bad: {}",
            getDynamicPropertiesStore().getLatestBlockHeaderHash());
      }
    }
  }

  private void postContractTrigger(final TransactionTrace trace, boolean remove) {
    if (eventPluginLoaded &&
        (EventPluginLoader.getInstance().isContractEventTriggerEnable()
            || EventPluginLoader.getInstance().isContractLogTriggerEnable()
            && trace.getRuntimeResult().getTriggerList().size() > 0)) {
      boolean result = false;
      // be careful, trace.getRuntimeResult().getTriggerList() should never return null
      for (ContractTrigger trigger : trace.getRuntimeResult().getTriggerList()) {
        if (trigger instanceof LogEventWrapper && EventPluginLoader.getInstance()
            .isContractEventTriggerEnable()) {
          ContractEventTriggerCapsule contractEventTriggerCapsule = new ContractEventTriggerCapsule(
              (LogEventWrapper) trigger);
          contractEventTriggerCapsule.getContractEventTrigger().setRemoved(remove);
          contractEventTriggerCapsule.setLatestSolidifiedBlockNumber(latestSolidifiedBlockNumber);
          result = triggerCapsuleQueue.offer(contractEventTriggerCapsule);
        } else if (trigger instanceof ContractLogTrigger && EventPluginLoader.getInstance()
            .isContractLogTriggerEnable()) {
          ContractLogTriggerCapsule contractLogTriggerCapsule = new ContractLogTriggerCapsule(
              (ContractLogTrigger) trigger);
          contractLogTriggerCapsule.getContractLogTrigger().setRemoved(remove);
          contractLogTriggerCapsule.setLatestSolidifiedBlockNumber(latestSolidifiedBlockNumber);
          result = triggerCapsuleQueue.offer(contractLogTriggerCapsule);
        }
        if (!result) {
          logger.info("too many tigger, lost contract log trigger: {}", trigger.getTransactionId());
        }
      }
    }
  }
}
