package org.bhc.core.actuator;

import com.google.protobuf.Any;
import org.bhc.common.storage.Deposit;
import org.bhc.core.capsule.TransactionResultCapsule;
import org.bhc.core.db.Manager;
import org.bhc.core.exception.ContractExeException;

public abstract class AbstractActuator implements Actuator {

  protected Any contract;
  protected Manager dbManager;

  public Deposit getDeposit() {
    return deposit;
  }

  public void setDeposit(Deposit deposit) {
    this.deposit = deposit;
  }

  protected Deposit deposit;

  AbstractActuator(Any contract, Manager dbManager) {
    this.contract = contract;
    this.dbManager = dbManager;
  }
}
