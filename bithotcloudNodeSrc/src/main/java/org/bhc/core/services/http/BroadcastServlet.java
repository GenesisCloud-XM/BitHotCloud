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

  /*һ���û�ͨ��http�ӿڽ�ǩ���õĽ��׷��͵�fullnode�ڵ㣬 fullnode�ڵ��Խ��׽�����صļ�飬 
   * Ȼ��ִ�н��ף����ִ�еĽ��û�����⣬ �ͻ�㲥���ס�
   * ��Ȼ�����ڵ��յ���ʹ㲥�Ľ���֮��Ҳ������ͬ�Ĳ�����*/
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
