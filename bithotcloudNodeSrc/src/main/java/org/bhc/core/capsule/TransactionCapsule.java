/*
 * java-tron is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * java-tron is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.bhc.core.capsule;

import static org.bhc.protos.Contract.AssetIssueContract;
import static org.bhc.protos.Contract.VoteAssetContract;
import static org.bhc.protos.Contract.VoteWitnessContract;
import static org.bhc.protos.Contract.WitnessCreateContract;
import static org.bhc.protos.Contract.WitnessUpdateContract;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.security.SignatureException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.bhc.common.crypto.ECKey;
import org.bhc.common.crypto.ECKey.ECDSASignature;
import org.bhc.common.runtime.Runtime;
import org.bhc.common.runtime.vm.program.Program.BadJumpDestinationException;
import org.bhc.common.runtime.vm.program.Program.BytecodeExecutionException;
import org.bhc.common.runtime.vm.program.Program.IllegalOperationException;
import org.bhc.common.runtime.vm.program.Program.JVMStackOverFlowException;
import org.bhc.common.runtime.vm.program.Program.OutOfEnergyException;
import org.bhc.common.runtime.vm.program.Program.OutOfMemoryException;
import org.bhc.common.runtime.vm.program.Program.OutOfTimeException;
import org.bhc.common.runtime.vm.program.Program.PrecompiledContractException;
import org.bhc.common.runtime.vm.program.Program.StackTooLargeException;
import org.bhc.common.runtime.vm.program.Program.StackTooSmallException;
import org.bhc.common.utils.ByteArray;
import org.bhc.common.utils.Sha256Hash;
import org.bhc.core.Wallet;
import org.bhc.core.db.AccountStore;
import org.bhc.core.db.Manager;
import org.bhc.core.db.TransactionTrace;
import org.bhc.core.exception.BadItemException;
import org.bhc.core.exception.PermissionException;
import org.bhc.core.exception.SignatureFormatException;
import org.bhc.core.exception.ValidateSignatureException;
import org.bhc.protos.Contract;
import org.bhc.protos.Contract.AccountCreateContract;
import org.bhc.protos.Contract.AccountPermissionUpdateContract;
import org.bhc.protos.Contract.AccountUpdateContract;
import org.bhc.protos.Contract.CreateSmartContract;
import org.bhc.protos.Contract.ExchangeCreateContract;
import org.bhc.protos.Contract.ExchangeInjectContract;
import org.bhc.protos.Contract.ExchangeTransactionContract;
import org.bhc.protos.Contract.ExchangeWithdrawContract;
import org.bhc.protos.Contract.FreezeBalanceContract;
import org.bhc.protos.Contract.ParticipateAssetIssueContract;
import org.bhc.protos.Contract.ProposalApproveContract;
import org.bhc.protos.Contract.ProposalCreateContract;
import org.bhc.protos.Contract.ProposalDeleteContract;
import org.bhc.protos.Contract.SetAccountIdContract;
import org.bhc.protos.Contract.TransferAssetContract;
import org.bhc.protos.Contract.TransferContract;
import org.bhc.protos.Contract.TriggerSmartContract;
import org.bhc.protos.Contract.UnfreezeAssetContract;
import org.bhc.protos.Contract.UnfreezeBalanceContract;
import org.bhc.protos.Contract.UpdateAssetContract;
import org.bhc.protos.Contract.UpdateEnergyLimitContract;
import org.bhc.protos.Contract.UpdateSettingContract;
import org.bhc.protos.Contract.WithdrawBalanceContract;
import org.bhc.protos.Protocol.Key;
import org.bhc.protos.Protocol.Permission;
import org.bhc.protos.Protocol.Permission.PermissionType;
import org.bhc.protos.Protocol.Transaction;
import org.bhc.protos.Protocol.Transaction.Contract.ContractType;
import org.bhc.protos.Protocol.Transaction.Result;
import org.bhc.protos.Protocol.Transaction.Result.contractResult;
import org.bhc.protos.Protocol.Transaction.raw;

@Slf4j(topic = "capsule")
public class TransactionCapsule implements ProtoCapsule<Transaction> {

  private Transaction transaction;
  @Setter
  private boolean isVerified = false;   //交易的验证标记

  @Setter
  @Getter
  private long blockNum = -1;

  @Getter
  @Setter
  private TransactionTrace trxTrace;

  /**
   * constructor TransactionCapsule.
   */
  public TransactionCapsule(Transaction trx) {
    this.transaction = trx;
  }

  /**
   * get account from bytes data.
   */
  public TransactionCapsule(byte[] data) throws BadItemException {
    try {
      this.transaction = Transaction.parseFrom(data);
    } catch (InvalidProtocolBufferException e) {
      throw new BadItemException("Transaction proto data parse exception");
    }
  }

  /*lll
  public TransactionCapsule(byte[] key, long value) throws IllegalArgumentException {
    if (!Wallet.addressValid(key)) {
      throw new IllegalArgumentException("Invalid address");
    }
    TransferContract transferContract = TransferContract.newBuilder()
        .setAmount(value)
        .setOwnerAddress(ByteString.copyFrom("0x0000000000000000000".getBytes()))
        .setToAddress(ByteString.copyFrom(key))
        .build();
    Transaction.raw.Builder transactionBuilder = Transaction.raw.newBuilder().addContract(
        Transaction.Contract.newBuilder().setType(ContractType.TransferContract).setParameter(
            Any.pack(transferContract)).build());
    logger.info("Transaction create succeeded锛�");
    transaction = Transaction.newBuilder().setRawData(transactionBuilder.build()).build();
  }*/

  public TransactionCapsule(AccountCreateContract contract, AccountStore accountStore) {
    AccountCapsule account = accountStore.get(contract.getOwnerAddress().toByteArray());
    if (account != null && account.getType() == contract.getType()) {
      return; // Account isexit
    }

    createTransaction(contract, ContractType.AccountCreateContract);
  }

  public TransactionCapsule(TransferContract contract, AccountStore accountStore) {
    Transaction.Contract.Builder contractBuilder = Transaction.Contract.newBuilder();

    AccountCapsule owner = accountStore.get(contract.getOwnerAddress().toByteArray());
    if (owner == null || owner.getBalance() < contract.getAmount()) {
      return; //The balance is not enough
    }

    createTransaction(contract, ContractType.TransferContract);
  }

  public TransactionCapsule(VoteWitnessContract voteWitnessContract) {
    createTransaction(voteWitnessContract, ContractType.VoteWitnessContract);
  }

  public TransactionCapsule(WitnessCreateContract witnessCreateContract) {
    createTransaction(witnessCreateContract, ContractType.WitnessCreateContract);
  }

  public TransactionCapsule(WitnessUpdateContract witnessUpdateContract) {
    createTransaction(witnessUpdateContract, ContractType.WitnessUpdateContract);
  }

  public TransactionCapsule(TransferAssetContract transferAssetContract) {
    createTransaction(transferAssetContract, ContractType.TransferAssetContract);
  }

  public TransactionCapsule(ParticipateAssetIssueContract participateAssetIssueContract) {
    createTransaction(participateAssetIssueContract, ContractType.ParticipateAssetIssueContract);
  }

  public TransactionCapsule(raw rawData, List<ByteString> signatureList) {
    this.transaction = Transaction.newBuilder().setRawData(rawData).addAllSignature(signatureList)
        .build();
  }

  public void resetResult() {
    if (this.getInstance().getRetCount() > 0) {
      this.transaction = this.getInstance().toBuilder().clearRet().build();
    }
  }

  public void setResult(TransactionResultCapsule transactionResultCapsule) {
    this.transaction = this.getInstance().toBuilder().addRet(transactionResultCapsule.getInstance())
        .build();
  }

  public void setReference(long blockNum, byte[] blockHash) {
    byte[] refBlockNum = ByteArray.fromLong(blockNum);
    Transaction.raw rawData = this.transaction.getRawData().toBuilder()
        .setRefBlockHash(ByteString.copyFrom(ByteArray.subArray(blockHash, 8, 16)))
        .setRefBlockBytes(ByteString.copyFrom(ByteArray.subArray(refBlockNum, 6, 8)))
        .build();
    this.transaction = this.transaction.toBuilder().setRawData(rawData).build();
  }

  /**
   * @param expiration must be in milliseconds format
   */
  public void setExpiration(long expiration) {
    Transaction.raw rawData = this.transaction.getRawData().toBuilder().setExpiration(expiration)
        .build();
    this.transaction = this.transaction.toBuilder().setRawData(rawData).build();
  }

  public long getExpiration() {
    return transaction.getRawData().getExpiration();
  }

  public void setTimestamp() {
    Transaction.raw rawData = this.transaction.getRawData().toBuilder()
        .setTimestamp(System.currentTimeMillis())
        .build();
    this.transaction = this.transaction.toBuilder().setRawData(rawData).build();
  }

  public long getTimestamp() {
    return transaction.getRawData().getTimestamp();
  }

  @Deprecated
  public TransactionCapsule(AssetIssueContract assetIssueContract) {
    createTransaction(assetIssueContract, ContractType.AssetIssueContract);
  }

  public TransactionCapsule(com.google.protobuf.Message message, ContractType contractType) {
    Transaction.raw.Builder transactionBuilder = Transaction.raw.newBuilder().addContract(
        Transaction.Contract.newBuilder().setType(contractType).setParameter(
            Any.pack(message)).build());
    transaction = Transaction.newBuilder().setRawData(transactionBuilder.build()).build();
  }

  @Deprecated
  public void createTransaction(com.google.protobuf.Message message, ContractType contractType) {
    Transaction.raw.Builder transactionBuilder = Transaction.raw.newBuilder().addContract(
        Transaction.Contract.newBuilder().setType(contractType).setParameter(
            Any.pack(message)).build());
    transaction = Transaction.newBuilder().setRawData(transactionBuilder.build()).build();
  }

  public Sha256Hash getMerkleHash() {
    byte[] transBytes = this.transaction.toByteArray();
    return Sha256Hash.of(transBytes);
  }

  private Sha256Hash getRawHash() {
    return Sha256Hash.of(this.transaction.getRawData().toByteArray());
  }

  public void sign(byte[] privateKey) {
    ECKey ecKey = ECKey.fromPrivate(privateKey);
    ECDSASignature signature = ecKey.sign(getRawHash().getBytes());
    ByteString sig = ByteString.copyFrom(signature.toByteArray());
    this.transaction = this.transaction.toBuilder().addSignature(sig).build();
  }

  public static long getWeight(Permission permission, byte[] address) {
    List<Key> list = permission.getKeysList();
    for (Key key : list) {
      if (key.getAddress().equals(ByteString.copyFrom(address))) {
        return key.getWeight();
      }
    }
    return 0;
  }

  //计算一个交易中所有签名的权重
  //task1:如果签名的个数超过了该权限允许的地址总个数,向上抛出异常
  //task2:统计所有签名的权重， 同时处理错误的情况
  public static long checkWeight(Permission permission, List<ByteString> sigs, byte[] hash,
      List<ByteString> approveList)
      throws SignatureException, PermissionException, SignatureFormatException {
    long currentWeight = 0;
//    if (signature.size() % 65 != 0) {
//      throw new SignatureFormatException("Signature size is " + signature.size());
//    }
    
    //----------------------------------------------------------------------
    //task1:如果签名的个数超过了该权限允许的地址总个数,向上抛出异常
    //---------------------------------------------------------------------- 
    if (sigs.size() > permission.getKeysCount()) {
      throw new PermissionException(
          "Signature count is " + (sigs.size()) + " more than key counts of permission : "
              + permission.getKeysCount());
    }
    HashMap addMap = new HashMap();
    for (ByteString sig : sigs) {
    	//检查签名格式是否正确
      if (sig.size() < 65) {
        throw new SignatureFormatException(
            "Signature size is " + sig.size());
      }
      //根据每个签名sig、数据内容hash得到签名者的账户地址，在permission中的key列表中查找这个账户地址对应的weight
      String base64 = TransactionCapsule.getBase64FromByteString(sig);
      byte[] address = ECKey.signatureToAddress(hash, base64);   //从签名中恢复地址
      long weight = getWeight(permission, address);  //获取这个地址的权重
      
      //如果这个地址的权重为0， 说明这个地址没有权利签名交易,向上抛出异常
      if (weight == 0) {
        throw new PermissionException(
            ByteArray.toHexString(sig.toByteArray()) + " is signed by " + Wallet
                .encode58Check(address) + " but it is not contained of permission.");
      }
      //检查是否存在一个地址多次签名的情况，否则向上抛出异常
      if (addMap.containsKey(base64)) {
        throw new PermissionException(Wallet.encode58Check(address) + " has signed twice!");
      }
      addMap.put(base64, weight);  //计算过的签名数据保存在addMap中，以便下一个签名进行检查。
      
      if (approveList != null) {
        approveList.add(ByteString.copyFrom(address)); //out put approve list.
      }
      
      //累加权重
      currentWeight += weight;
    }
    return currentWeight;
  }

  public void addSign(byte[] privateKey, AccountStore accountStore)
      throws PermissionException, SignatureException, SignatureFormatException {
    Transaction.Contract contract = this.transaction.getRawData().getContract(0);
    int permissionId = contract.getPermissionId();
    byte[] owner = getOwner(contract);
    AccountCapsule account = accountStore.get(owner);
    if (account == null) {
      throw new PermissionException("Account is not exist!");
    }
    Permission permission = account.getPermissionById(permissionId);
    if (permission == null) {
      throw new PermissionException("permission isn't exit");
    }
    if (permissionId != 0) {
      if (permission.getType() != PermissionType.Active) {
        throw new PermissionException("Permission type is error");
      }
      //check oprations
      if (!Wallet.checkPermissionOprations(permission, contract)) {
        throw new PermissionException("Permission denied");
      }
    }
    List<ByteString> approveList = new ArrayList<>();
    ECKey ecKey = ECKey.fromPrivate(privateKey);
    byte[] address = ecKey.getAddress();
    if (this.transaction.getSignatureCount() > 0) {
      checkWeight(permission, this.transaction.getSignatureList(), this.getRawHash().getBytes(),
          approveList);
      if (approveList.contains(ByteString.copyFrom(address))) {
        throw new PermissionException(Wallet.encode58Check(address) + " had signed!");
      }
    }
    // 不是任何人都能签名上来的，必须是在合约的Permission列表中指定的账户才行。签名权重为0无效
    long weight = getWeight(permission, address);
    if (weight == 0) {
      throw new PermissionException(
          ByteArray.toHexString(privateKey) + "'s address is " + Wallet
              .encode58Check(address) + " but it is not contained of permission.");
    }
    ECDSASignature signature = ecKey.sign(getRawHash().getBytes());
    ByteString sig = ByteString.copyFrom(signature.toByteArray());
    this.transaction = this.transaction.toBuilder().addSignature(sig).build();
  }

  // todo mv this static function to capsule util
  public static byte[] getOwner(Transaction.Contract contract) {
    ByteString owner;
    try {
      Any contractParameter = contract.getParameter();
      switch (contract.getType()) {
        case AccountCreateContract:
          owner = contractParameter.unpack(AccountCreateContract.class).getOwnerAddress();
          break;
        case TransferContract:
          owner = contractParameter.unpack(TransferContract.class).getOwnerAddress();
          break;
        case TransferAssetContract:
          owner = contractParameter.unpack(TransferAssetContract.class).getOwnerAddress();
          break;
        case VoteAssetContract:
          owner = contractParameter.unpack(VoteAssetContract.class).getOwnerAddress();
          break;
        case VoteWitnessContract:
          owner = contractParameter.unpack(VoteWitnessContract.class).getOwnerAddress();
          break;
        case WitnessCreateContract:
          owner = contractParameter.unpack(WitnessCreateContract.class).getOwnerAddress();
          break;
        case AssetIssueContract:
          owner = contractParameter.unpack(AssetIssueContract.class).getOwnerAddress();
          break;
        case WitnessUpdateContract:
          owner = contractParameter.unpack(WitnessUpdateContract.class).getOwnerAddress();
          break;
        case ParticipateAssetIssueContract:
          owner = contractParameter.unpack(ParticipateAssetIssueContract.class).getOwnerAddress();
          break;
        case AccountUpdateContract:
          owner = contractParameter.unpack(AccountUpdateContract.class).getOwnerAddress();
          break;
        case FreezeBalanceContract:
          owner = contractParameter.unpack(FreezeBalanceContract.class).getOwnerAddress();
          break;
        case UnfreezeBalanceContract:
          owner = contractParameter.unpack(UnfreezeBalanceContract.class).getOwnerAddress();
          break;
        case UnfreezeAssetContract:
          owner = contractParameter.unpack(UnfreezeAssetContract.class).getOwnerAddress();
          break;
        case WithdrawBalanceContract:
          owner = contractParameter.unpack(WithdrawBalanceContract.class).getOwnerAddress();
          break;
        case CreateSmartContract:
          owner = contractParameter.unpack(Contract.CreateSmartContract.class).getOwnerAddress();
          break;
        case TriggerSmartContract:
          owner = contractParameter.unpack(Contract.TriggerSmartContract.class).getOwnerAddress();
          break;
        case UpdateAssetContract:
          owner = contractParameter.unpack(UpdateAssetContract.class).getOwnerAddress();
          break;
        case ProposalCreateContract:
          owner = contractParameter.unpack(ProposalCreateContract.class).getOwnerAddress();
          break;
        case ProposalApproveContract:
          owner = contractParameter.unpack(ProposalApproveContract.class).getOwnerAddress();
          break;
        case ProposalDeleteContract:
          owner = contractParameter.unpack(ProposalDeleteContract.class).getOwnerAddress();
          break;
        case SetAccountIdContract:
          owner = contractParameter.unpack(SetAccountIdContract.class).getOwnerAddress();
          break;
//        case BuyStorageContract:
//          owner = contractParameter.unpack(BuyStorageContract.class).getOwnerAddress();
//          break;
//        case BuyStorageBytesContract:
//          owner = contractParameter.unpack(BuyStorageBytesContract.class).getOwnerAddress();
//          break;
//        case SellStorageContract:
//          owner = contractParameter.unpack(SellStorageContract.class).getOwnerAddress();
//          break;
        case UpdateSettingContract:
          owner = contractParameter.unpack(UpdateSettingContract.class)
              .getOwnerAddress();
          break;
        case UpdateEnergyLimitContract:
          owner = contractParameter.unpack(UpdateEnergyLimitContract.class)
              .getOwnerAddress();
          break;
        case ExchangeCreateContract:
          owner = contractParameter.unpack(ExchangeCreateContract.class).getOwnerAddress();
          break;
        case ExchangeInjectContract:
          owner = contractParameter.unpack(ExchangeInjectContract.class).getOwnerAddress();
          break;
        case ExchangeWithdrawContract:
          owner = contractParameter.unpack(ExchangeWithdrawContract.class).getOwnerAddress();
          break;
        case ExchangeTransactionContract:
          owner = contractParameter.unpack(ExchangeTransactionContract.class).getOwnerAddress();
          break;
        case AccountPermissionUpdateContract:
          owner = contractParameter.unpack(AccountPermissionUpdateContract.class).getOwnerAddress();
          break;
        // todo add other contract
        default:
          return null;
      }
      return owner.toByteArray();
    } catch (Exception ex) {
      logger.error(ex.getMessage());
      return null;
    }
  }

  // todo mv this static function to capsule util 获得合约内的接收方地址，只用于转账、转账代币、申购代币三种合约
  public static byte[] getToAddress(Transaction.Contract contract) {
    ByteString to;
    try {
      Any contractParameter = contract.getParameter();
      switch (contract.getType()) {
        case TransferContract:
          to = contractParameter.unpack(TransferContract.class).getToAddress();
          break;
        case TransferAssetContract:
          to = contractParameter.unpack(TransferAssetContract.class).getToAddress();
          break;
        case ParticipateAssetIssueContract:
          to = contractParameter.unpack(ParticipateAssetIssueContract.class).getToAddress();
          break;
        // todo add other contract

        default:
          return null;
      }
      return to.toByteArray();
    } catch (Exception ex) {
      logger.error(ex.getMessage());
      return null;
    }
  }

  // todo mv this static function to capsule util
  public static long getCallValue(Transaction.Contract contract) {
    int energyForTrx;
    try {
      Any contractParameter = contract.getParameter();
      long callValue;
      switch (contract.getType()) {
        case TriggerSmartContract:
          return contractParameter.unpack(TriggerSmartContract.class).getCallValue();

        case CreateSmartContract:
          return contractParameter.unpack(CreateSmartContract.class).getNewContract()
              .getCallValue();
        default:
          return 0L;
      }
    } catch (Exception ex) {
      logger.error(ex.getMessage());
      return 0L;
    }
  }

  // todo mv this static function to capsule util
  public static long getCallTokenValue(Transaction.Contract contract) {
    int energyForTrx;
    try {
      Any contractParameter = contract.getParameter();
      long callValue;
      switch (contract.getType()) {
        case TriggerSmartContract:
          return contractParameter.unpack(TriggerSmartContract.class).getCallTokenValue();

        case CreateSmartContract:
          return contractParameter.unpack(CreateSmartContract.class).getCallTokenValue();
        default:
          return 0L;
      }
    } catch (Exception ex) {
      logger.error(ex.getMessage());
      return 0L;
    }
  }

  public static String getBase64FromByteString(ByteString sign) {
    byte[] r = sign.substring(0, 32).toByteArray();
    byte[] s = sign.substring(32, 64).toByteArray();
    byte v = sign.byteAt(64);
    if (v < 27) {
      v += 27; //revId -> v
    }
    ECDSASignature signature = ECDSASignature.fromComponents(r, s, v);
    return signature.toBase64();
  }

  //验证一个交易的签名
  //task1:根据交易的中的permissionid，从账户中获取permission对象
  //task2:根据账户中定义的permission， 检查交易中的所有签名总权重是否达到正确的阀值
  public static boolean validateSignature(Transaction transaction,
      byte[] hash, Manager manager)
      throws PermissionException, SignatureException, SignatureFormatException {
    AccountStore accountStore = manager.getAccountStore();
    
    //----------------------------------------------------------------------
    //task1:根据交易的中的permissionid，从账户中获取permission对象
    //----------------------------------------------------------------------
    //获取交易中的contract 
    Transaction.Contract contract = transaction.getRawData().getContractList().get(0);
    int permissionId = contract.getPermissionId();  //获取permissionID
    byte[] owner = getOwner(contract);   //交易调用方、合约持有人
    AccountCapsule account = accountStore.get(owner);  //获取合约持有人的账户
    Permission permission = null;
    
    // 如果交易发起方账户在链上不存在，则创建一个默认的permission来进行验证，这种情况一般发生在创建账户的交易中。  
    if (account == null) {
    	//交易中的指定的permissionid 为0,使用默认的权限
      if (permissionId == 0) {
        permission = AccountCapsule.getDefaultPermission(ByteString.copyFrom(owner));
      }
      //交易中的指定permissionid为1， 使用默认的active权限
      if (permissionId == 2) {
        permission = AccountCapsule
            .createDefaultActivePermission(ByteString.copyFrom(owner), manager);
      }
    } else {  //从账户中获取相应的permissionid
      permission = account.getPermissionById(permissionId);
    }
    //如果账户中的permission不存在，向上抛出异常
    if (permission == null) {
      throw new PermissionException("permission isn't exit");
    }
    //检查操作权限 
    //如果permission类型是active的，还需要检查交易发起方是否具备当前的交易类型的操作权限，checkPermissionOprations函数做操作权限的检查。
    if (permissionId != 0) {  //如果permisionID不能于0， 只能等于2就是active权限，否则就是错误
      if (permission.getType() != PermissionType.Active) {
        throw new PermissionException("Permission type is error");
      }
      //check oprations  //检查可执行的操作权限(就是允许的各种ContractType),如果没有操作权限，向上抛出异常
      if (!Wallet.checkPermissionOprations(permission, contract)) {
        throw new PermissionException("Permission denied");
      }
    }
    
    //----------------------------------------------------------------------
    //task2:根据账户中定义的permission， 检查交易中的所有签名总权重是否达到正确的阀值
    //----------------------------------------------------------------------
    long weight = checkWeight(permission, transaction.getSignatureList(), hash, null);
    if (weight >= permission.getThreshold()) {
      return true;
    }
    return false;
  }

  /**  这才是真正的验证签名入口函数
   * //验证签名
     //task1:如果之前已经验证通过， 直接返回
     //task2:验证签名总数是否在正确的范围之内
     //task3:验证签名,并处理异常
   * validate signature
   */
  public boolean validateSignature(Manager manager)
      throws ValidateSignatureException {
	//task1:如果之前已经验证通过， 直接返回  
    if (isVerified == true) {
      return true;
    }
    
    //task2:验证签名总数是否在正确的范围之内
    //由于波场支持多重签名，所以交易中可以存在多个签名，但是每个权限（Permission）最多只能支持5个签名,所以这里判断签名的个数必须大于0，或者小于等于5。
    //----------------------------------------------------------------------
    //如果签名的总数小于0， 则抛出异常
    if (this.transaction.getSignatureCount() <= 0
        || this.transaction.getRawData().getContractCount() <= 0) {
      throw new ValidateSignatureException("miss sig or contract");
    }
    //如果超过5个签名， 向上抛出异常
    if (this.transaction.getSignatureCount() > manager.getDynamicPropertiesStore().getTotalSignNum()) {
      throw new ValidateSignatureException("too many signatures");
    }
    byte[] hash = this.getRawHash().getBytes();
    
    //task3:验证签名,并处理异常
    try {
      if (!validateSignature(this.transaction, hash, manager)) {
        isVerified = false;
        throw new ValidateSignatureException("sig error");
      }
    } catch (SignatureException e) {
      isVerified = false;
      throw new ValidateSignatureException(e.getMessage());
    } catch (PermissionException e) {
      isVerified = false;
      throw new ValidateSignatureException(e.getMessage());
    } catch (SignatureFormatException e) {
      isVerified = false;
      throw new ValidateSignatureException(e.getMessage());
    }

    //验证通过，标记验证通过
    isVerified = true;
    return true;
  }

  public Sha256Hash getTransactionId() {
    return getRawHash();
  }

  @Override
  public byte[] getData() {
    return this.transaction.toByteArray();
  }

  public long getSerializedSize() {
    return this.transaction.getSerializedSize();
  }

  public long getResultSerializedSize() {
    long size = 0;
    for (Result result : this.transaction.getRetList()) {
      size += result.getSerializedSize();
    }
    return size;
  }

  @Override
  public Transaction getInstance() {
    return this.transaction;
  }

  private StringBuffer toStringBuff = new StringBuffer();

  @Override
  public String toString() {

    toStringBuff.setLength(0);
    toStringBuff.append("TransactionCapsule \n[ ");

    toStringBuff.append("hash=").append(getTransactionId()).append("\n");
    AtomicInteger i = new AtomicInteger();
    if (!getInstance().getRawData().getContractList().isEmpty()) {
      toStringBuff.append("contract list:{ ");
      getInstance().getRawData().getContractList().forEach(contract -> {
        toStringBuff.append("[" + i + "] ").append("type: ").append(contract.getType())
            .append("\n");
        toStringBuff.append("from address=").append(getOwner(contract)).append("\n");
        toStringBuff.append("to address=").append(getToAddress(contract)).append("\n");
        if (contract.getType().equals(ContractType.TransferContract)) {
          TransferContract transferContract;
          try {
            transferContract = contract.getParameter()
                .unpack(TransferContract.class);
            toStringBuff.append("transfer amount=").append(transferContract.getAmount())
                .append("\n");
          } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
          }
        } else if (contract.getType().equals(ContractType.TransferAssetContract)) {
          TransferAssetContract transferAssetContract;
          try {
            transferAssetContract = contract.getParameter()
                .unpack(TransferAssetContract.class);
            toStringBuff.append("transfer asset=").append(transferAssetContract.getAssetName())
                .append("\n");
            toStringBuff.append("transfer amount=").append(transferAssetContract.getAmount())
                .append("\n");
          } catch (InvalidProtocolBufferException e) {
            e.printStackTrace();
          }
        }
        if (this.transaction.getSignatureList().size() >= i.get() + 1) {
          toStringBuff.append("sign=").append(getBase64FromByteString(
              this.transaction.getSignature(i.getAndIncrement()))).append("\n");
        }
      });
      toStringBuff.append("}\n");
    } else {
      toStringBuff.append("contract list is empty\n");
    }

    toStringBuff.append("]");
    return toStringBuff.toString();
  }

  public void setResult(Runtime runtime) {
    RuntimeException exception = runtime.getResult().getException();
    if (Objects.isNull(exception) && StringUtils
        .isEmpty(runtime.getRuntimeError()) && !runtime.getResult().isRevert()) {
      this.setResultCode(contractResult.SUCCESS);
      return;
    }
    if (runtime.getResult().isRevert()) {
      this.setResultCode(contractResult.REVERT);
      return;
    }
    if (exception instanceof IllegalOperationException) {
      this.setResultCode(contractResult.ILLEGAL_OPERATION);
      return;
    }
    if (exception instanceof OutOfEnergyException) {
      this.setResultCode(contractResult.OUT_OF_ENERGY);
      return;
    }
    if (exception instanceof BadJumpDestinationException) {
      this.setResultCode(contractResult.BAD_JUMP_DESTINATION);
      return;
    }
    if (exception instanceof OutOfTimeException) {
      this.setResultCode(contractResult.OUT_OF_TIME);
      return;
    }
    if (exception instanceof OutOfMemoryException) {
      this.setResultCode(contractResult.OUT_OF_MEMORY);
      return;
    }
    if (exception instanceof PrecompiledContractException) {
      this.setResultCode(contractResult.PRECOMPILED_CONTRACT);
      return;
    }
    if (exception instanceof StackTooSmallException) {
      this.setResultCode(contractResult.STACK_TOO_SMALL);
      return;
    }
    if (exception instanceof StackTooLargeException) {
      this.setResultCode(contractResult.STACK_TOO_LARGE);
      return;
    }
    if (exception instanceof JVMStackOverFlowException) {
      this.setResultCode(contractResult.JVM_STACK_OVER_FLOW);
      return;
    }
    this.setResultCode(contractResult.UNKNOWN);
    return;
  }

  public void setResultCode(contractResult code) {
    Result ret = Result.newBuilder().setContractRet(code).build();
    if (this.transaction.getRetCount() > 0) {
      ret = this.transaction.getRet(0).toBuilder().setContractRet(code).build();

      this.transaction = transaction.toBuilder().setRet(0, ret).build();
      return;
    }
    this.transaction = transaction.toBuilder().addRet(ret).build();
  }

  public contractResult getContractRet() {
    if (this.transaction.getRetCount() <= 0) {
      return null;
    }
    return this.transaction.getRet(0).getContractRet();
  }
}
