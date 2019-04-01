package org.inventivetalent.minetile.router;

import org.junit.jupiter.api.Test;

public class NumberTest {

	@Test
	public void mathRoundTest0() {
		double in = -256.75;
		int tileSize = 256;
		int rounded = (int) Math.round(in / (tileSize*2));
		assert rounded == -1;
	}

	@Test
	public void mathRoundTest1() {
		double in = -256;
		int tileSize = 256;
		int rounded = (int) Math.round(in / (tileSize*2));
		assert rounded != -1;
	}

	@Test
	public void mathRoundTest2() {
		assert (int)Math.round(-0.5) != -1;
	}

	@Test
	public void mathRoundTest3() {
		assert (int)Math.round(-0.50146484) == -1;
	}



}
