package org.bhc.common.runtime;

import lombok.Setter;
import org.bhc.common.runtime.vm.program.InternalTransaction.TrxType;
import org.bhc.common.runtime.vm.program.ProgramResult;
import org.bhc.core.exception.ContractExeException;
import org.bhc.core.exception.ContractValidateException;
import org.bhc.core.exception.VMIllegalException;


public interface Runtime {

  boolean isCallConstant() throws ContractValidateException;

  void execute() throws ContractValidateException, ContractExeException, VMIllegalException;

  void go();

  TrxType getTrxType();

  void finalization();

  ProgramResult getResult();

  String getRuntimeError();

  void setEnableEventLinstener(boolean enableEventLinstener);
}
