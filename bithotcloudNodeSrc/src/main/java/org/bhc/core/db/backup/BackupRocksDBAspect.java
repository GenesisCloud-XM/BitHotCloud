package org.bhc.core.db.backup;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.AfterThrowing;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.aspectj.lang.annotation.Pointcut;
import org.springframework.beans.factory.annotation.Autowired;
import org.bhc.common.backup.BackupManager;
import org.bhc.common.backup.BackupManager.BackupStatusEnum;
import org.bhc.core.capsule.BlockCapsule;
import org.bhc.core.config.args.Args;

@Slf4j
@Aspect
public class BackupRocksDBAspect {

  @Autowired
  private BackupDbUtil util;

  @Autowired
  private BackupManager backupManager;


  @Pointcut("execution(** org.bhc.core.db.Manager.pushBlock(..)) && args(block)")
  public void pointPushBlock(BlockCapsule block) {

  }

  @Before("pointPushBlock(block)")
  public void backupDb(BlockCapsule block) {
    //SR-Master Node do not backup db;
    if (Args.getInstance().isWitness() && !(backupManager.getStatus() == BackupStatusEnum.SLAVER)) {
      return;
    }

    //backup db when reach frequency.
    if (block.getNum() % Args.getInstance().getDbBackupConfig().getFrequency() == 0) {
      try {
        util.doBackup(block);
      } catch (Exception e) {
        logger.error("backup db failure: {}", e);
      }
    }
  }

  @AfterThrowing("pointPushBlock(block)")
  public void logErrorPushBlock(BlockCapsule block) {
    logger.info("AfterThrowing pushBlock");
  }
}