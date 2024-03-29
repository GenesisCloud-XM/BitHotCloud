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

import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.bhc.common.utils.ByteArray;
import org.bhc.core.db.Manager;
import org.bhc.protos.Contract.AssetIssueContract;
import org.bhc.protos.Contract.AssetIssueContract.FrozenSupply;

@Slf4j(topic = "capsule")
public class AssetIssueCapsule implements ProtoCapsule<AssetIssueContract> {

  private AssetIssueContract assetIssueContract;

  /**
   * get asset issue contract from bytes data.
   */
  public AssetIssueCapsule(byte[] data) {
    try {
      this.assetIssueContract = AssetIssueContract.parseFrom(data);
    } catch (InvalidProtocolBufferException e) {
      logger.debug(e.getMessage());
    }
  }

  public AssetIssueCapsule(AssetIssueContract assetIssueContract) {
    this.assetIssueContract = assetIssueContract;
  }

  public byte[] getData() {
    return this.assetIssueContract.toByteArray();
  }

  @Override
  public AssetIssueContract getInstance() {
    return this.assetIssueContract;
  }

  @Override
  public String toString() {
    return this.assetIssueContract.toString();
  }

  public ByteString getName() {
    return this.assetIssueContract.getName();
  }

  public void setId(String id) {
    this.assetIssueContract = this.assetIssueContract.toBuilder()
        .setId(id)
        .build();
  }

  public String getId() {
    return this.assetIssueContract.getId();
  }

  public void setPrecision(int precision) {
    this.assetIssueContract = this.assetIssueContract.toBuilder()
        .setPrecision(precision)
        .build();
  }

  public int getPrecision() {
    return this.assetIssueContract.getPrecision();
  }

  public void setOrder(long order) {
    this.assetIssueContract = this.assetIssueContract.toBuilder()
        .setOrder(order)
        .build();
  }

  public long getOrder() {
    return this.assetIssueContract.getOrder();
  }

  public byte[] createDbV2Key() {
    return ByteArray.fromString(this.assetIssueContract.getId());
  }

  public byte[] createDbKey() {
//    long order = getOrder();
//    if (order == 0) {
//      return getName().toByteArray();
//    }
//    String name = new String(getName().toByteArray(), Charset.forName("UTF-8"));
//    String nameKey = createDbKeyString(name, order);
//    return nameKey.getBytes();
    return getName().toByteArray();
  }

  public byte[] createDbKeyFinal(Manager manager) {
    if (manager.getDynamicPropertiesStore().getAllowSameTokenName() == 0) {
      return createDbKey();
    } else {
      return createDbV2Key();
    }
  }

  public static String createDbKeyString(String name, long order) {
    return name + "_" + order;
  }

  public int getNum() {
    return this.assetIssueContract.getNum();
  }

  public int getTrxNum() {
    return this.assetIssueContract.getTrxNum();
  }

  public long getStartTime() {
    return this.assetIssueContract.getStartTime();
  }

  public long getEndTime() {
    return this.assetIssueContract.getEndTime();
  }

  public ByteString getOwnerAddress() {
    return this.assetIssueContract.getOwnerAddress();
  }

  public int getFrozenSupplyCount() {
    return getInstance().getFrozenSupplyCount();
  }

  public List<FrozenSupply> getFrozenSupplyList() {
    return getInstance().getFrozenSupplyList();
  }

  public long getFrozenSupply() {
    List<FrozenSupply> frozenList = getFrozenSupplyList();
    final long[] frozenBalance = {0};
    frozenList.forEach(frozen -> frozenBalance[0] = Long.sum(frozenBalance[0],
        frozen.getFrozenAmount()));
    return frozenBalance[0];
  }

  public long getFreeAssetNetLimit() {
    return this.assetIssueContract.getFreeAssetNetLimit();
  }

  public void setFreeAssetNetLimit(long newLimit) {
    this.assetIssueContract = this.assetIssueContract.toBuilder()
        .setFreeAssetNetLimit(newLimit).build();
  }

  public long getPublicFreeAssetNetLimit() {
    return this.assetIssueContract.getPublicFreeAssetNetLimit();
  }

  public void setPublicFreeAssetNetLimit(long newPublicLimit) {
    this.assetIssueContract = this.assetIssueContract.toBuilder()
        .setPublicFreeAssetNetLimit(newPublicLimit).build();
  }

  public long getPublicFreeAssetNetUsage() {
    return this.assetIssueContract.getPublicFreeAssetNetUsage();
  }

  public void setPublicFreeAssetNetUsage(long value) {
    this.assetIssueContract = this.assetIssueContract.toBuilder()
        .setPublicFreeAssetNetUsage(value).build();
  }

  public long getPublicLatestFreeNetTime() {
    return this.assetIssueContract.getPublicLatestFreeNetTime();
  }

  public void setPublicLatestFreeNetTime(long time) {
    this.assetIssueContract = this.assetIssueContract.toBuilder()
        .setPublicLatestFreeNetTime(time).build();
  }

  public void setUrl(ByteString newUrl) {
    this.assetIssueContract = this.assetIssueContract.toBuilder()
        .setUrl(newUrl).build();
  }

  public void setDescription(ByteString description) {
    this.assetIssueContract = this.assetIssueContract.toBuilder()
        .setDescription(description).build();
  }
}
