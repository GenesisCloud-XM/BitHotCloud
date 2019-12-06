package org.bhc.core.services.http;

import java.io.IOException;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.bhc.api.GrpcAPI;
import org.bhc.core.Wallet;
import org.bhc.protos.Protocol.Transaction;

@Component
@Slf4j(topic = "API")
public class BroadcastServlet extends HttpServlet {

  @Autowired
  private Wallet wallet;

  /*一般用户通过http接口将签名好的交易发送到fullnode节点， fullnode节点会对交易进行相关的检查， 
   * 然后执行交易，如果执行的结果没有问题， 就会广播交易。
   * 当然其他节点收到这笔广播的交易之后也会做相同的操作。*/
  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      String input = request.getReader().lines()
          .collect(Collectors.joining(System.lineSeparator()));
      Util.checkBodySize(input);
      Transaction transaction = Util.packTransaction(input);
      GrpcAPI.Return retur = wallet.broadcastTransaction(transaction);
      response.getWriter().println(JsonFormat.printToString(retur));
    } catch (Exception e) {
      logger.debug("Exception: {}", e.getMessage());
      try {
        response.getWriter().println(Util.printErrorMsg(e));
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }
  }
}
