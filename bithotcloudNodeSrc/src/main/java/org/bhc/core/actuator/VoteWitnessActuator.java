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

  //���validate�������ڴ�������ʱ�Զ���ִ����������á��������ִ�к�Լǰ����һ��
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

  //����ͶƱ��Լ����
  private void countVoteAccount(VoteWitnessContract voteContract, Deposit deposit) {
    byte[] ownerAddress = voteContract.getOwnerAddress().toByteArray();    //ͶƱ��

    VotesCapsule votesCapsule;   //ͶƱ�˵�ͶƱ����
    VotesStore votesStore = dbManager.getVotesStore();         //����ѡƱ�ֿ�
    AccountStore accountStore = dbManager.getAccountStore();   //����ϵͳ�е��˻��ֿ⣬���������û��ĵ�ַ

    //��ȡͶƱ���˻�����
    AccountCapsule accountCapsule = (Objects.isNull(getDeposit())) ? accountStore.get(ownerAddress)
        : getDeposit().getAccount(ownerAddress);

    //��ȡͶƱ�˵�ͶƱ����votesCapsule�� 
    //�ȴӻ����в��ң��ٴ�ѡƱ�ֿ��в��ң����ѡƱ�ֿ��в����ھʹ�������
    if (!Objects.isNull(getDeposit())) {
      VotesCapsule vCapsule = getDeposit().getVotesCapsule(ownerAddress);
      if (Objects.isNull(vCapsule)) {
        votesCapsule = new VotesCapsule(voteContract.getOwnerAddress(), accountCapsule.getVotesList());
      } else {
        votesCapsule = vCapsule;
      }
    } else if (!votesStore.has(ownerAddress)) {
      //ע�⣬ ÿһ��ѡ��ʱ�����ѡƱ�ֿ�����յģ�����֮����û���һ��ͶƱ�����е���� Ҫ�����û���ͶƱ��¼����Ȼ��浽ͶƱ�ֿ��С�
      //�ر�ע��������캯���ڶ��������������û��˻��б����ѡƱ�б���ʱ��û�����أ������浽votesCapsule�ڲ���old_votes�С� ����Ψһ��old_votes�б�ֵ�ĵط���
      votesCapsule = new VotesCapsule(voteContract.getOwnerAddress(), accountCapsule.getVotesList());
    } else {
      votesCapsule = votesStore.get(ownerAddress);
    }

    accountCapsule.clearVotes();   //ͶƱ���˻��е�ͶƱ��¼Ҫ��գ� ��Ϊ�µ�ͶƱ��¼�Ḳ�ǵ���
    votesCapsule.clearNewVotes();  //ͶƱ�˵�ͶƱ���ݰ��� old_votes��new_votes����Vote�б����������ѡƱ�б�

    //ͶƱ��Լ�а�����ͶƱ�˵�ͶƱ�б�����Ͷ�������ѡ�ڵ�����ѡƱ��ͨ��ѭ������Ͷ��ÿһ����ѡ�ڵ��ѡƱ
    voteContract.getVotesList().forEach(vote -> {
      logger.debug("countVoteAccount,address[{}]",
          ByteArray.toHexString(vote.getVoteAddress().toByteArray()));
      //��ͶƱ�����е�new_votes�б������ �µ����ݣ���ѡ�ڵ��ַ+��Ʊ��
      votesCapsule.addNewVotes(vote.getVoteAddress(), vote.getVoteCount());
      //ͶƱ���˻���ͶƱ�б������ �µ����ݣ���ѡ�ڵ��ַ+��Ʊ��
      accountCapsule.addVotes(vote.getVoteAddress(), vote.getVoteCount());
    });

    //���浽��������ݿ��У�����ͶƱ���˻���ѡƱ�ֿ��¼��
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
