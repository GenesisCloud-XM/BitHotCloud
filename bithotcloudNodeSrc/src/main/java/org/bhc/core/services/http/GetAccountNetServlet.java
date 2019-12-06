package org.bhc.core.services.http;

import com.google.protobuf.ByteString;
import java.io.IOException;
import java.util.stream.Collectors;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.bhc.api.GrpcAPI.AccountNetMessage;
import org.bhc.common.utils.ByteArray;
import org.bhc.core.Wallet;
import org.bhc.protos.Protocol.Account;


@Component
@Slf4j(topic = "API")
public class GetAccountNetServlet extends HttpServlet {

  @Autowired
  private Wallet wallet;

  protected void doGet(HttpServletRequest request, HttpServletResponse response) {
    try {
      String address = request.getParameter("address");
      AccountNetMessage reply = wallet
          .getAccountNet(ByteString.copyFrom(ByteArray.fromHexString(address)));
      if (reply != null) {
        response.getWriter().println(JsonFormat.printToString(reply));
      } else {
        response.getWriter().println("{}");
      }
    } catch (Exception e) {
      logger.debug("Exception: {}", e.getMessage());
      try {
        response.getWriter().println(Util.printErrorMsg(e));
      } catch (IOException ioe) {
        logger.debug("IOException: {}", ioe.getMessage());
      }
    }
  }

  protected void doPost(HttpServletRequest request, HttpServletResponse response) {
    try {
      String account = request.getReader().lines()
          .collect(Collectors.joining(System.lineSeparator()));
      Util.checkBodySize(account);
      Account.Builder build = Account.newBuilder();
      JsonFormat.merge(account, build);
      AccountNetMessage reply = wallet.getAccountNet(build.getAddress());
      if (reply != null) {
        response.getWriter().println(JsonFormat.printToString(reply));
      } else {
        response.getWriter().println("{}");
      }
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
