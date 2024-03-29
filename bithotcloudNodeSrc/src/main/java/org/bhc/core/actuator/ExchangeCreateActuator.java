package org.bhc.core.actuator;

import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import org.bhc.common.utils.StringUtil;
import org.bhc.core.Wallet;
import org.bhc.core.capsule.AccountCapsule;
import org.bhc.core.capsule.ExchangeCapsule;
import org.bhc.core.capsule.TransactionResultCapsule;
import org.bhc.core.capsule.utils.TransactionUtil;
import org.bhc.core.db.Manager;
import org.bhc.core.exception.BalanceInsufficientException;
import org.bhc.core.exception.ContractExeException;
import org.bhc.core.exception.ContractValidateException;
import org.bhc.protos.Contract.ExchangeCreateContract;
import org.bhc.protos.Protocol.Transaction.Result.code;

@Slf4j(topic = "actuator")
public class ExchangeCreateActuator extends AbstractActuator {

  ExchangeCreateActuator(final Any contract, final Manager dbManager) {
    super(contract, dbManager);
  }

  @Override
  public boolean execute(TransactionResultCapsule ret) throws ContractExeException {
    long fee = calcFee();
    try {
      final ExchangeCreateContract exchangeCreateContract = this.contract
          .unpack(ExchangeCreateContract.class);
      AccountCapsule accountCapsule = dbManager.getAccountStore()
          .get(exchangeCreateContract.getOwnerAddress().toByteArray());

      byte[] firstTokenID = exchangeCreateContract.getFirstTokenId().toByteArray();
      byte[] secondTokenID = exchangeCreateContract.getSecondTokenId().toByteArray();
      long firstTokenBalance = exchangeCreateContract.getFirstTokenBalance();
      long secondTokenBalance = exchangeCreateContract.getSecondTokenBalance();

      long newBalance = accountCapsule.getBalance() - fee;

      accountCapsule.setBalance(newBalance);

      if (Arrays.equals(firstTokenID, "_".getBytes())) {
        accountCapsule.setBalance(newBalance - firstTokenBalance);
      } else {
        accountCapsule.reduceAssetAmountV2(firstTokenID, firstTokenBalance, dbManager);
      }

      if (Arrays.equals(secondTokenID, "_".getBytes())) {
        accountCapsule.setBalance(newBalance - secondTokenBalance);
      } else {
        accountCapsule.reduceAssetAmountV2(secondTokenID, secondTokenBalance, dbManager);
      }

      long id = dbManager.getDynamicPropertiesStore().getLatestExchangeNum() + 1;
      long now = dbManager.getHeadBlockTimeStamp();
      if (dbManager.getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
        //save to old asset store
        ExchangeCapsule exchangeCapsule =
            new ExchangeCapsule(
                exchangeCreateContract.getOwnerAddress(),
                id,
                now,
                firstTokenID,
                secondTokenID
            );
        exchangeCapsule.setBalance(firstTokenBalance, secondTokenBalance);
        dbManager.getExchangeStore().put(exchangeCapsule.createDbKey(), exchangeCapsule);

        //save to new asset store
        if (!Arrays.equals(firstTokenID, "_".getBytes())) {
          String firstTokenRealID = dbManager.getAssetIssueStore().get(firstTokenID).getId();
          firstTokenID = firstTokenRealID.getBytes();
        }
        if (!Arrays.equals(secondTokenID, "_".getBytes())) {
          String secondTokenRealID = dbManager.getAssetIssueStore().get(secondTokenID).getId();
          secondTokenID = secondTokenRealID.getBytes();
        }
      }

      {
        // only save to new asset store
        ExchangeCapsule exchangeCapsuleV2 =
            new ExchangeCapsule(
                exchangeCreateContract.getOwnerAddress(),
                id,
                now,
                firstTokenID,
                secondTokenID
            );
        exchangeCapsuleV2.setBalance(firstTokenBalance, secondTokenBalance);
        dbManager.getExchangeV2Store().put(exchangeCapsuleV2.createDbKey(), exchangeCapsuleV2);
      }

      dbManager.getAccountStore().put(accountCapsule.createDbKey(), accountCapsule);
      dbManager.getDynamicPropertiesStore().saveLatestExchangeNum(id);

      dbManager.adjustBalance(dbManager.getAccountStore().getBlackhole().createDbKey(), fee);

      ret.setExchangeId(id);
      ret.setStatus(fee, code.SUCESS);
    } catch (BalanceInsufficientException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage(), e);
      ret.setStatus(fee, code.FAILED);
      throw new ContractExeException(e.getMessage());
    }
    return true;
  }

  @Override
  public boolean validate() throws ContractValidateException {
    if (this.contract == null) {
      throw new ContractValidateException("No contract!");
    }
    if (this.dbManager == null) {
      throw new ContractValidateException("No dbManager!");
    }
    if (!this.contract.is(ExchangeCreateContract.class)) {
      throw new ContractValidateException(
          "contract type error,expected type [ExchangeCreateContract],real type[" + contract
              .getClass() + "]");
    }
    final ExchangeCreateContract contract;
    try {
      contract = this.contract.unpack(ExchangeCreateContract.class);
    } catch (InvalidProtocolBufferException e) {
      throw new ContractValidateException(e.getMessage());
    }

    byte[] ownerAddress = contract.getOwnerAddress().toByteArray();
    String readableOwnerAddress = StringUtil.createReadableString(ownerAddress);

    if (!Wallet.addressValid(ownerAddress)) {
      throw new ContractValidateException("Invalid address");
    }

    if (!this.dbManager.getAccountStore().has(ownerAddress)) {
      throw new ContractValidateException("account[" + readableOwnerAddress + "] not exists");
    }

    AccountCapsule accountCapsule = this.dbManager.getAccountStore().get(ownerAddress);

    if (accountCapsule.getBalance() < calcFee()) {
      throw new ContractValidateException("No enough balance for exchange create fee!");
    }

    byte[] firstTokenID = contract.getFirstTokenId().toByteArray();
    byte[] secondTokenID = contract.getSecondTokenId().toByteArray();
    long firstTokenBalance = contract.getFirstTokenBalance();
    long secondTokenBalance = contract.getSecondTokenBalance();

    if (dbManager.getDynamicPropertiesStore().getAllowSameTokenName() == 1) {
      if (!Arrays.equals(firstTokenID, "_".getBytes()) && !TransactionUtil.isNumber(firstTokenID)) {
        throw new ContractValidateException("first token id is not a valid number");
      }
      if (!Arrays.equals(secondTokenID, "_".getBytes()) && !TransactionUtil
          .isNumber(secondTokenID)) {
        throw new ContractValidateException("second token id is not a valid number");
      }
    }

    if (Arrays.equals(firstTokenID, secondTokenID)) {
      throw new ContractValidateException("cannot exchange same tokens");
    }

    if (firstTokenBalance <= 0 || secondTokenBalance <= 0) {
      throw new ContractValidateException("token balance must greater than zero");
    }

    long balanceLimit = dbManager.getDynamicPropertiesStore().getExchangeBalanceLimit();
    if (firstTokenBalance > balanceLimit || secondTokenBalance > balanceLimit) {
      throw new ContractValidateException("token balance must less than " + balanceLimit);
    }

    if (Arrays.equals(firstTokenID, "_".getBytes())) {
      if (accountCapsule.getBalance() < (firstTokenBalance + calcFee())) {
        throw new ContractValidateException("balance is not enough");
      }
    } else {
      if (!accountCapsule.assetBalanceEnoughV2(firstTokenID, firstTokenBalance, dbManager)) {
        throw new ContractValidateException("first token balance is not enough");
      }
    }

    if (Arrays.equals(secondTokenID, "_".getBytes())) {
      if (accountCapsule.getBalance() < (secondTokenBalance + calcFee())) {
        throw new ContractValidateException("balance is not enough");
      }
    } else {
      if (!accountCapsule.assetBalanceEnoughV2(secondTokenID, secondTokenBalance, dbManager)) {
        throw new ContractValidateException("second token balance is not enough");
      }
    }

    return true;
  }


  @Override
  public ByteString getOwnerAddress() throws InvalidProtocolBufferException {
    return contract.unpack(ExchangeCreateContract.class).getOwnerAddress();
  }

  @Override
  public long calcFee() {
    return dbManager.getDynamicPropertiesStore().getExchangeCreateFee();
  }

}
