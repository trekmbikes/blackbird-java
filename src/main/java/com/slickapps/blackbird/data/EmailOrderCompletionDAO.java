package com.slickapps.blackbird.data;

import static com.slickapps.blackbird.Main.TWO;
import static com.slickapps.blackbird.util.FormatUtil.formatCurrency;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import com.slickapps.blackbird.listener.DefaultBlackbirdEventListener;
import com.slickapps.blackbird.model.ExchangePairInMarket;
import com.slickapps.blackbird.model.Parameters;

public class EmailOrderCompletionDAO extends DefaultBlackbirdEventListener {

	private Parameters params;
	private Properties props;

	public EmailOrderCompletionDAO(Parameters params) {
		this.params = params;
		props = params.getAllParams();
	}

	@Override
	public void orderComplete(ExchangePairInMarket p) {
		sendEmail(p);
	}

	private static String buildBody(ExchangePairInMarket res) {
		DateTimeFormatter dtf = DateTimeFormatter.ofPattern("MM/dd h:mm a");

		StringBuilder sb = new StringBuilder();
		String tdStyle = "font-family:Georgia;font-size:11px;border-color:#A1A1A1;border-width:1px;border-style:solid;padding:2px;";
		String captionStyle = "font-family:Georgia;font-size:13px;font-weight:normal;color:#0021BF;padding-bottom:6px;text-align:left;";
		String tableTitleStyle = "font-family:Georgia;font-variant:small-caps;font-size:13px;text-align:center;border-color:#A1A1A1;border-width:1px;border-style:solid;background-color:#EAEAEA;";

		sb.append("<html><body>") //
				.append("    <table style=\"border-width:0px;border-collapse:collapse;text-align:center;\">") //
				.append("      <caption style=\"" + captionStyle + "\">Blackbird Trade Completion - ID " + res.getId()
						+ "</caption>") //
				.append("      <tr style=\"" + tableTitleStyle + "\">") //
				.append("        <td style=\"" + tdStyle + "width:120px;\">Entry Date</td>") //
				.append("        <td style=\"" + tdStyle + "width:120px;\">Exit Date</td>") //
				.append("        <td style=\"" + tdStyle + "width:70px;\">Long</td>") //
				.append("        <td style=\"" + tdStyle + "width:70px;\">Short</td>") //
				.append("        <td style=\"" + tdStyle + "width:70px;\">Exposure</td>") //
				.append("        <td style=\"" + tdStyle + "width:70px;\">Profit</td>") //
				.append("      </tr>") //
				.append("      <tr>") //
				.append("        <td style=\"" + tdStyle + "\">" + dtf.format(res.getEntryTime()) + "</td>") //
				.append("        <td style=\"" + tdStyle + "\">" + dtf.format(res.getExitTime()) + "</td>") //
				.append("        <td style=\"" + tdStyle + "\">" + res.getLongExchangeName() + " - "
						+ res.getLongCurrencyPair() + "</td>") //
				.append("        <td style=\"" + tdStyle + "\">" + res.getShortExchangeName() + " - "
						+ res.getShortCurrencyPair() + "</td>") //
				.append("        <td style=\"" + tdStyle + "\">$" + res.getExposure().multiply(TWO) + "</td>") //
				.append("        <td style=\"" + tdStyle + "\">$" + res.getFinalProfitAfterFees() + "</td>")
				.append("</tr>") //
				.append("    </table>") //
				.append("</body></html>");

		return sb.toString();
	}

	public void sendEmail(ExchangePairInMarket res) {
		if (!params.sendEmail)
			return;

		String body = buildBody(res);

		Session session = Session.getDefaultInstance(props, new javax.mail.Authenticator() {
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication(params.senderUsername, params.senderPassword);
			}
		});

		try {
			Message message = new MimeMessage(session);
			message.setFrom(new InternetAddress(params.senderAddress));
			message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(params.receiverAddress));
			String subject = "Blackbird Arb Completion - ID " + res.getId();
			if (res.isBothExitOrdersFilled()) {
				BigDecimal finalProfit = res.getFinalProfitAfterFees();
				subject += " (" + (finalProfit.signum() == 1 ? "+" : "")
						+ formatCurrency(res.getLongCurrencyPair().counter, finalProfit) + ")";
			} else {
				subject += " (potential error - exit orders unfilled)";
			}
			message.setSubject(subject);
			message.setContent(body, "text/html");

			Transport.send(message);
		} catch (MessagingException e) {
			throw new RuntimeException(e);
		}
	}

}
