package org.bhc.core.net.message;

import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import org.bhc.common.utils.Sha256Hash;
import org.bhc.protos.Protocol;
import org.bhc.protos.Protocol.Inventory;
import org.bhc.protos.Protocol.Inventory.InventoryType;

//具体类别的消息， 这是存货消息类型
public class InventoryMessage extends TronMessage {

  //对应协议中的message Inventory， 关键内容是type=类型，ids=消息体字节数组
  protected Inventory inv;

  public InventoryMessage(byte[] data) throws Exception {
    this.type = MessageTypes.INVENTORY.asByte();
    this.data = data;
    this.inv = Protocol.Inventory.parseFrom(data);
  }

  public InventoryMessage(Inventory inv) {
    this.inv = inv;
    this.type = MessageTypes.INVENTORY.asByte();
    this.data = inv.toByteArray();
  }

  public InventoryMessage(List<Sha256Hash> hashList, InventoryType type) {
    Inventory.Builder invBuilder = Inventory.newBuilder();

    for (Sha256Hash hash : hashList) {
      invBuilder.addIds(hash.getByteString());
    }
    invBuilder.setType(type);
    inv = invBuilder.build();
    this.type = MessageTypes.INVENTORY.asByte();
    this.data = inv.toByteArray();
  }

  @Override
  public Class<?> getAnswerMessage() {
    return null;
  }

  public Inventory getInventory() {
    return inv;
  }

  public MessageTypes getInvMessageType() {
    return getInventoryType().equals(InventoryType.BLOCK) ? MessageTypes.BLOCK : MessageTypes.TRX;

  }

  public InventoryType getInventoryType() {
    return inv.getType();
  }

  @Override
  public String toString() {
    Deque<Sha256Hash> hashes = new LinkedList<>(getHashList());
    StringBuilder builder = new StringBuilder();
    builder.append(super.toString()).append("invType: ").append(getInvMessageType())
        .append(", size: ").append(hashes.size())
        .append(", First hash: ").append(hashes.peekFirst());
    if (hashes.size() > 1) {
      builder.append(", End hash: ").append(hashes.peekLast());
    }
    return builder.toString();
  }

  public List<Sha256Hash> getHashList() {
    return getInventory().getIdsList().stream()
        .map(hash -> Sha256Hash.wrap(hash.toByteArray()))
        .collect(Collectors.toList());
  }

}
