package stest.tron.wallet.contract.scenario;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeSuite;
import org.testng.annotations.Test;
import org.bhc.api.GrpcAPI.AccountResourceMessage;
import org.bhc.api.WalletGrpc;
import org.bhc.common.crypto.ECKey;
import org.bhc.common.utils.ByteArray;
import org.bhc.common.utils.Utils;
import org.bhc.core.Wallet;
import org.bhc.protos.Protocol.Account;
import org.bhc.protos.Protocol.SmartContract;
import stest.tron.wallet.common.client.Configuration;
import stest.tron.wallet.common.client.Parameter.CommonConstant;
import stest.tron.wallet.common.client.utils.PublicMethed;

@Slf4j
public class ContractScenario008 {

  private final String testKey002 = Configuration.getByPath("testng.conf")
      .getString("foundationAccount.key2");
  private final byte[] fromAddress = PublicMethed.getFinalAddress(testKey002);

  private ManagedChannel channelFull = null;
  private WalletGrpc.WalletBlockingStub blockingStubFull = null;
  private String fullnode = Configuration.getByPath("testng.conf")
      .getStringList("fullnode.ip.list").get(1);
  private Long maxFeeLimit = Configuration.getByPath("testng.conf")
      .getLong("defaultParameter.maxFeeLimit");

  ECKey ecKey1 = new ECKey(Utils.getRandom());
  byte[] contract008Address = ecKey1.getAddress();
  String contract008Key = ByteArray.toHexString(ecKey1.getPrivKeyBytes());

  @BeforeSuite
  public void beforeSuite() {
    Wallet wallet = new Wallet();
    Wallet.setAddressPreFixByte(CommonConstant.ADD_PRE_FIX_BYTE_MAINNET);
  }

  /**
   * constructor.
   */

  @BeforeClass(enabled = true)
  public void beforeClass() {
    channelFull = ManagedChannelBuilder.forTarget(fullnode)
        .usePlaintext(true)
        .build();
    blockingStubFull = WalletGrpc.newBlockingStub(channelFull);
  }

  @Test(enabled = true)
  public void deployErc721CryptoKitties() {
    ecKey1 = new ECKey(Utils.getRandom());
    contract008Address = ecKey1.getAddress();
    contract008Key = ByteArray.toHexString(ecKey1.getPrivKeyBytes());
    PublicMethed.printAddress(contract008Key);
    Assert.assertTrue(PublicMethed.sendcoin(contract008Address, 5000000000L, fromAddress,
        testKey002, blockingStubFull));
    Assert.assertTrue(PublicMethed.freezeBalanceGetEnergy(contract008Address, 1000000L,
        3, 1, contract008Key, blockingStubFull));
    AccountResourceMessage accountResource = PublicMethed.getAccountResource(contract008Address,
        blockingStubFull);
    Long energyLimit = accountResource.getEnergyLimit();
    Long energyUsage = accountResource.getEnergyUsed();
    Account account = PublicMethed.queryAccount(contract008Key, blockingStubFull);
    logger.info("before balance is " + Long.toString(account.getBalance()));
    logger.info("before energy limit is " + Long.toString(energyLimit));
    logger.info("before energy usage is " + Long.toString(energyUsage));
    Long shortFeeLimit = 900L;
    String contractName = "Cat";
    String code = Configuration.getByPath("testng.conf")
        .getString("code.code_ContractScenario008_deployErc721CryptoKitties");
    String abi = Configuration.getByPath("testng.conf")
        .getString("abi.abi_ContractScenario008_deployErc721CryptoKitties");
    byte[] contractAddress = PublicMethed.deployContract(contractName, abi, code, "", shortFeeLimit,
        0L, 100, null, contract008Key, contract008Address, blockingStubFull);

    contractAddress = PublicMethed.deployContract(contractName, abi, code, "", maxFeeLimit,
        0L, 100, null, contract008Key, contract008Address, blockingStubFull);

    final SmartContract smartContract = PublicMethed.getContract(contractAddress, blockingStubFull);
    accountResource = PublicMethed.getAccountResource(contract008Address, blockingStubFull);
    energyLimit = accountResource.getEnergyLimit();
    energyUsage = accountResource.getEnergyUsed();
    account = PublicMethed.queryAccount(contract008Key, blockingStubFull);
    logger.info("after balance is " + Long.toString(account.getBalance()));
    logger.info("after energy limit is " + Long.toString(energyLimit));
    logger.info("after energy usage is " + Long.toString(energyUsage));
    Assert.assertTrue(energyLimit > 0);
    Assert.assertTrue(energyUsage > 0);
    Assert.assertFalse(smartContract.getAbi().toString().isEmpty());
    Assert.assertTrue(smartContract.getName().equalsIgnoreCase(contractName));
    Assert.assertFalse(smartContract.getBytecode().toString().isEmpty());
  }

  /**
   * constructor.
   */

  @AfterClass
  public void shutdown() throws InterruptedException {
    if (channelFull != null) {
      channelFull.shutdown().awaitTermination(5, TimeUnit.SECONDS);
    }
  }
}


