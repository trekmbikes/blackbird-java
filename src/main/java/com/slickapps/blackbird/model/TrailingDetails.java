package com.slickapps.blackbird.model;

import java.math.BigDecimal;

public class TrailingDetails {

	// ------------------------------ Fields

	private BigDecimal trailingStop;
	private int trailingStopApprovalCount = 1;

	// ------------------------------ Business Methods

	public boolean hasTrailingSpread() {
		return trailingStop != null;
	}

	public void reset() {
		trailingStop = null;
		resetRequiredConfirmationPeriods();
	}

	public void incrementTrailingStopApprovalCount() {
		trailingStopApprovalCount++;
	}
	
	public void resetRequiredConfirmationPeriods() {
		setTrailingStopApprovalCount(1);
	}
	
	// ------------------------------ Accessor Methods

	public BigDecimal getTrailingStop() {
		return trailingStop;
	}

	public void setTrailingStop(BigDecimal trailingSpread) {
		this.trailingStop = trailingSpread;
	}

	public int getTrailingStopApprovalCount() {
		return trailingStopApprovalCount;
	}

	public void setTrailingStopApprovalCount(int spreadCount) {
		this.trailingStopApprovalCount = spreadCount;
	}

}
