package org.bhc.core.actuator;

import static org.bhc.core.actuator.ActuatorConstant.ACCOUNT_EXCEPTION_STR;
import static org.bhc.core.actuator.ActuatorConstant.NOT_EXIST_STR;
import static org.bhc.core.actuator.ActuatorConstant.WITNESS_EXCEPTION_STR;

import com.google.common.math.LongMath;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Iterator;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.bhc.common.storage.Deposit;
import org.bhc.common.utils.ByteArray;
import org.bhc.common.utils.StringUtil;
import org.bhc.core.Wallet;
import org.bhc.core.capsule.AccountCapsule;
import org.bhc.core.capsule.TransactionResultCapsule;
import org.bhc.core.capsule.VotesCapsule;
import org.bhc.core.config.Parameter.ChainConstant;
import org.bhc.core.db.AccountStore;
import org.bhc.core.db.Manager;
import org.bhc.core.db.VotesStore;
import org.bhc.core.db.WitnessStore;
import org.bhc.core.exception.ContractExeException;
import org.bhc.core.exception.ContractValidateException;
import org.bhc.protos.Contract.VoteWitnessContract;
import org.bhc.protos.Contract.VoteWitnessContract.Vote;
import org.bhc.protos.Protocol.Transaction.Result.code;

@Slf4j(topic = "actuator")
public class VoteWitnessActuator extends AbstractActuator {


  VoteWitnessActuator(Any contract, Manager dbManager) {
    super(contract, dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    long fee = calcFee();
    try {
      VoteWitnessContract voteContract = contract.unpack(VoteWitnessContract.class);
      countVoteAccount(voteContract, getDeposit());
      ret.setStatus(fee, code.SUCESS);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }
    return true;
  }

  //这个validate函数会在创建交易时自动被执行器对象调用。在虚拟机执行合约前调用一次
  @Override
  public boolean validate() throws ContractValidateException {
    if (this.contract == null) {
      throw new ContractValidateException("No contract!");
    }
    if (dbManager == null && (getDeposit() == null || getDeposit().getDbManager() == null)) {
      throw new ContractValidateException("No dbManager!");
    }
    if (!this.contract.is(VoteWitnessContract.class)) {
      throw new ContractValidateException(
          "contract type error,expected type [VoteWitnessContract],real type[" + contract
              .getClass() + "]");
    }
    final VoteWitnessContract contract;
    try {
      contract = this.contract.unpack(VoteWitnessContract.class);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }
    if (!Wallet.addressValid(contract.getOwnerAddress().toByteArray())) {
      throw new ContractValidateException("Invalid address");
    }
    byte[] ownerAddress = contract.getOwnerAddress().toByteArray();
    String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);

    AccountStore accountStore = dbManager.getAccountStore();
    WitnessStore witnessStore = dbManager.getWitnessStore();

    if (contract.getVotesCount() == 0) {
      throw new ContractValidateException(
          "VoteNumber must more than 0");
    }
    int maxVoteNumber = ChainConstant.MAX_VOTE_NUMBER;
    if (contract.getVotesCount() > maxVoteNumber) {
      throw new ContractValidateException(
          "VoteNumber more than maxVoteNumber " + maxVoteNumber);
    }
    try {
      Iterator<Vote> iterator = contract.getVotesList().iterator();
      Long sum = 0L;
      while (iterator.hasNext()) {
        Vote vote = iterator.next();
        byte[] witnessCandidate = vote.getVoteAddress().toByteArray();
        if (!Wallet.addressValid(witnessCandidate)) {
          throw new ContractValidateException("Invalid vote address!");
        }
        long voteCount = vote.getVoteCount();
        if (voteCount <= 0) {
          throw new ContractValidateException("vote count must be greater than 0");
        }
        String readableWitnessAddress = StringUtil.createReadableString(vote.getVoteAddress());
        if (!Objects.isNull(getDeposit())) {
          if (Objects.isNull(getDeposit().getAccount(witnessCandidate))) {
            throw new ContractValidateException(
                ACCOUNT_EXCEPTION_STR + readableWitnessAddress + NOT_EXIST_STR);
          }
        } else if (!accountStore.has(witnessCandidate)) {
          throw new ContractValidateException(
              ACCOUNT_EXCEPTION_STR + readableWitnessAddress + NOT_EXIST_STR);
        }
        if (!Objects.isNull(getDeposit())) {
          if (Objects.isNull(getDeposit().getWitness(witnessCandidate))) {
            throw new ContractValidateException(
                WITNESS_EXCEPTION_STR + readableWitnessAddress + NOT_EXIST_STR);
          }
        } else if (!witnessStore.has(witnessCandidate)) {
          throw new ContractValidateException(
              WITNESS_EXCEPTION_STR + readableWitnessAddress + NOT_EXIST_STR);
        }
        sum = LongMath.checkedAdd(sum, vote.getVoteCount());
      }

      AccountCapsule accountCapsule =
          (Objects.isNull(getDeposit())) ? accountStore.get(ownerAddress)
              : getDeposit().getAccount(ownerAddress);
      if (accountCapsule == null) {
        throw new ContractValidateException(
            ACCOUNT_EXCEPTION_STR + readableOwnerAddress + NOT_EXIST_STR);
      }

      long tronPower = accountCapsule.getTronPower();

      sum = LongMath.checkedMultiply(sum, 1000000L); //trx -> drop. The vote count is based on CGM
      if (sum > tronPower) {
        throw new ContractValidateException(
            "The total number of votes[" + sum + "] is greater than the tronPower[" + tronPower
                + "]");
      }
    } catch (ArithmeticException e) {
      logger.debug(e.getMessage(), e);
      throw new ContractValidateException(e.getMessage());
    }

    return true;
  }

  //处理投票合约事务
  private void countVoteAccount(VoteWitnessContract voteContract, Deposit deposit) {
    byte[] ownerAddress = voteContract.getOwnerAddress().toByteArray();    //投票人

    VotesCapsule votesCapsule;   //投票人的投票数据
    VotesStore votesStore = dbManager.getVotesStore();         //这是选票仓库
    AccountStore accountStore = dbManager.getAccountStore();   //这是系统中的账户仓库，包含所有用户的地址

    //获取投票人账户对象
    AccountCapsule accountCapsule = (Objects.isNull(getDeposit())) ? accountStore.get(ownerAddress)
        : getDeposit().getAccount(ownerAddress);

    //获取投票人的投票数据votesCapsule。 
    //先从缓存中查找，再从选票仓库中查找，最后选票仓库中不存在就创建出来
    if (!Objects.isNull(getDeposit())) {
      VotesCapsule vCapsule = getDeposit().getVotesCapsule(ownerAddress);
      if (Objects.isNull(vCapsule)) {
        votesCapsule = new VotesCapsule(voteContract.getOwnerAddress(), accountCapsule.getVotesList());
      } else {
        votesCapsule = vCapsule;
      }
    } else if (!votesStore.has(ownerAddress)) {
      //注意， 每一轮选举时间过后，选票仓库是清空的，所以之后的用户第一次投票会运行到这里。 要创建用户的投票记录对象，然后存到投票仓库中。
      //特别注意这个构造函数第二个参数，这是用户账户中保存的选票列表，此时还没更新呢，将保存到votesCapsule内部的old_votes中。 这是唯一对old_votes列表赋值的地方。
      votesCapsule = new VotesCapsule(voteContract.getOwnerAddress(), accountCapsule.getVotesList());
    } else {
      votesCapsule = votesStore.get(ownerAddress);
    }

    accountCapsule.clearVotes();   //投票人账户中的投票记录要清空， 因为新的投票记录会覆盖掉。
    votesCapsule.clearNewVotes();  //投票人的投票数据包含 old_votes、new_votes两个Vote列表，这里清空新选票列表

    //投票合约中包含了投票人的投票列表，包含投给多个候选节点对象的选票，通过循环处理投给每一个候选节点的选票
    voteContract.getVotesList().forEach(vote -> {
      logger.debug("countVoteAccount,address[{}]",
          ByteArray.toHexString(vote.getVoteAddress().toByteArray()));
      //在投票数据中的new_votes列表中添加 新的数据（候选节点地址+得票）
      votesCapsule.addNewVotes(vote.getVoteAddress(), vote.getVoteCount());
      //投票人账户的投票列表中添加 新的数据（候选节点地址+得票）
      accountCapsule.addVotes(vote.getVoteAddress(), vote.getVoteCount());
    });

    //保存到缓存或数据库中，更新投票人账户、选票仓库记录。
    if (Objects.isNull(deposit)) {
      accountStore.put(accountCapsule.createDbKey(), accountCapsule);
      votesStore.put(ownerAddress, votesCapsule);
    } else {
      // cache
      deposit.putAccountValue(accountCapsule.createDbKey(), accountCapsule);
      deposit.putVoteValue(ownerAddress, votesCapsule);
    }

  }

  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(VoteWitnessContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return 0;
  }

}
