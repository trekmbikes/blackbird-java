package com.slickapps.blackbird.test;

import java.util.Arrays;

import org.junit.Assert;
import org.junit.Test;

import com.slickapps.blackbird.AbstractBlackbirdTest;
import com.slickapps.blackbird.TestMain;
import com.slickapps.blackbird.exchanges.MockExchange;
import com.slickapps.blackbird.model.Parameters;

public class TrailingStopTest extends AbstractBlackbirdTest {

	@Test
	public void happyPath() throws Exception {
		Parameters params = createTestParameters();
		exchanges = Arrays.asList(new MockExchange("A", 0.2, 9, new double[][] {
				// ------ Entry
				// move to 10, activate entry trailing
				{ 3, 10 },
				// increase to 11, still trailing
				{ 4, 20 },
				// drop to 10, activate entry trades
				{ 5, 19 },
				// ------ Exit
				// drop to 9, activate exit trailing
				{ 6, 9 },
				// drop to 8, still trailing
				{ 7, 8 },
				// increase to 8.5, activate exit trades
				{ 8, 8.5 }, //
		}).makeShortable(), new MockExchange("B", 0.2, 9));
		main = new TestMain(params, exchanges);
		main.initResources();
		advanceToStep(3);
		Assert.assertTrue(main.inMarketPairs().isEmpty());
		advanceToStep(5);
		Assert.assertEquals(2, main.inMarketPairs().size());
		advanceToStep(6);
		Assert.assertNotNull(main.getExitTrailingDetails());
		advanceToStep(8);
		Assert.assertTrue(main.inMarketPairs().isEmpty());
	}

	@Test
	public void testMultipleTrailingConfirmationPeriodsForEntry() throws Exception {
		Parameters params = createTestParameters();

		params.trailingRequiredConfirmationPeriods = 2;
		exchanges = Arrays.asList(new MockExchange("A", 0.2, 10, new double[][] {
				// ------ Entry; target = $11 (+10%)
				// move to 12, activate entry trailing, spread = ~20%, trailing = ~15% (5% less
				// per params)
				{ 1, 12 },
				// increase to 15, spread up to ~50%, increase trailing spread to ~45% (still
				// trailing)
				{ 2, 15 },
				// drop to 13, spread = ~30%, below our trailing spread so approval count = 2/2,
				// don't activate entry trades yet
				{ 3, 13 },
				/*
				 * increase to $14.70, spread = ~47% (so main spread exceeds our previous
				 * trailing val of 45% above), reset approval counter and new trailing val of
				 * $13.78
				 */
				{ 4, 14.7 },
				// drop to 13.70, approval count = 1/2 so don't activate entry trades yet
				{ 5, 13.7 },
				// drop to 12, approval count = 2/2 so activate entry trades
				{ 6, 12 },
				//
		}).makeShortable(), new MockExchange("B", 0.2, 10));
		main = new TestMain(params, exchanges);
		main.initResources();
		advanceToStep(1);
		Assert.assertTrue(main.inMarketPairs().isEmpty());
		Assert.assertNotNull(main.getEntryTrailingDetails());
		Assert.assertTrue(main.getEntryTrailingDetails().hasTrailingSpread());
		Assert.assertEquals(main.getEntryTrailingDetails().getTrailingStopApprovalCount(), 1);
		advanceToStep(2);
		Assert.assertTrue(main.inMarketPairs().isEmpty());
		Assert.assertEquals(main.getEntryTrailingDetails().getTrailingStopApprovalCount(), 1);
		advanceToStep(3);
		Assert.assertTrue(main.inMarketPairs().isEmpty());
		Assert.assertEquals(main.getEntryTrailingDetails().getTrailingStopApprovalCount(), 2);
		advanceToStep(4);
		Assert.assertTrue(main.inMarketPairs().isEmpty());
		Assert.assertTrue(main.getEntryTrailingDetails().hasTrailingSpread());
		Assert.assertEquals(main.getEntryTrailingDetails().getTrailingStopApprovalCount(), 1);
		advanceToStep(5);
		Assert.assertTrue(main.inMarketPairs().isEmpty());
		Assert.assertEquals(main.getEntryTrailingDetails().getTrailingStopApprovalCount(), 2);
		advanceToStep(6);
		Assert.assertEquals(main.inMarketPairs().size(), 2);
		Assert.assertFalse(main.getEntryTrailingDetails().hasTrailingSpread());
	}

	@Test
	public void testMultipleTrailingConfirmationPeriodsForExit() throws Exception {
		Parameters params = createTestParameters();

		params.trailingRequiredConfirmationPeriods = 2;
		exchanges = Arrays.asList(new MockExchange("A", 0.2, 10, new double[][] {
				// ------ Entry; target = $11 (+10%)
				// move to 15, activate entry trailing, spread = ~50%, trailing = ~45% (5% less
				// per params)
				{ 1, 15 },
				// drop to 13, spread 30%
				{ 2, 13 },
				// drop to 12, spread = 20%, activate entry trades
				{ 3, 12 },
				/*
				 * increase to $14.70, spread = ~47% (so main spread exceeds our previous
				 * trailing val of 45% above), reset approval counter and new trailing val of
				 * $13.78
				 */
				{ 4, 5 },
				// drop to 13.70, approval count = 1/2 so don't activate entry trades yet
				{ 5, 7 },
				// drop to 12, approval count = 2/2 so activate entry trades
				{ 6, 6 },
				//
		}).makeShortable(), new MockExchange("B", 0.2, 10));
		main = new TestMain(params, exchanges);
		main.initResources();
		advanceToStep(3);
		Assert.assertEquals(main.inMarketPairs().size(), 2);
		advanceToStep(4);
		Assert.assertEquals(main.inMarketPairs().size(), 2);
		Assert.assertTrue(main.getExitTrailingDetails().hasTrailingSpread());
		Assert.assertEquals(main.getExitTrailingDetails().getTrailingStopApprovalCount(), 1);
		advanceToStep(5);
		Assert.assertEquals(main.inMarketPairs().size(), 2);
		Assert.assertTrue(main.getExitTrailingDetails().hasTrailingSpread());
		Assert.assertEquals(main.getExitTrailingDetails().getTrailingStopApprovalCount(), 2);
		advanceToStep(6);
		Assert.assertTrue(main.inMarketPairs().isEmpty());
		Assert.assertFalse(main.getExitTrailingDetails().hasTrailingSpread());
	}

}
