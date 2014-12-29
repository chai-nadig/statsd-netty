package com.flozano.statsd.netty;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.everyItem;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flozano.statsd.metrics.Count;
import com.flozano.statsd.metrics.Gauge;
import com.flozano.statsd.metrics.Metric;
import com.flozano.statsd.mock.NettyUDPServer;
import com.flozano.statsd.mock.ThreadedUDPServer;
import com.flozano.statsd.mock.UDPServer;

@RunWith(Parameterized.class)
public class IntegrationTest {

	static final Logger LOGGER = LoggerFactory.getLogger(IntegrationTest.class);

	static final int PORT = 8125;

	@Parameters(name = "{index}: items={0}, rcvbuf={2}, flushProbability={3}, server={1}")
	public static Collection<Object[]> params() {
		List<Object[]> params = new LinkedList<>();
		for (int i : Arrays.asList(10, 100, 500)) {
			for (Class<? extends UDPServer> serverClass : Arrays
					.<Class<? extends UDPServer>> asList(
							ThreadedUDPServer.class, NettyUDPServer.Nio.class
					/* ,NettyUDPServer.Oio.class */)) {
				for (int recvbufValue : Arrays.asList(50_000, 250_000,
						1_000_000)) {
					for (int flushProbability : Arrays.asList(20, 80)) {
						params.add(new Object[] { i, serverClass, recvbufValue,
								flushProbability });
					}
				}
			}
		}
		return params;
	}

	private final int numberOfItems;

	private final Class<? extends UDPServer> serverClass;

	private final int recvbufValue;

	private final int flushProbability;

	public IntegrationTest(int numberOfItems,
			Class<? extends UDPServer> serverClass, int recvbufValue,
			int flushProbability) {
		this.numberOfItems = numberOfItems;
		this.serverClass = serverClass;
		this.recvbufValue = recvbufValue;
		this.flushProbability = flushProbability;
	}

	@Test
	public void testManyCalls() throws Exception {
		try (UDPServer server = newServer(numberOfItems)) {
			try (NettyStatsDClientImpl c = newClient()) {
				List<CompletableFuture<Void>> css = new ArrayList<>(
						numberOfItems);
				for (int i = 0; i < numberOfItems; i++) {
					css.add(c.send(new Count("example", 1)));
				}

				CompletableFuture.allOf(
						css.toArray(new CompletableFuture[numberOfItems])).get(
						10, TimeUnit.SECONDS);
				LOGGER.info("All items sent: {}", numberOfItems);

				assertServer(server, "example:1|c");
			}
		}
	}

	@Test
	public void testSingleCall() throws Exception {
		try (UDPServer server = newServer(numberOfItems)) {
			try (NettyStatsDClientImpl c = newClient()) {
				Metric[] items = new Metric[numberOfItems];
				for (int i = 0; i < numberOfItems; i++) {
					items[i] = new Gauge("example", 2);
				}

				c.send(items).get(10, TimeUnit.SECONDS);
				LOGGER.info("All items sent: {}", numberOfItems);

				assertServer(server, "example:2|g");
			}
		}
	}

	private void assertServer(UDPServer server, String expectedValue) {
		assertTrue("All items were not received",
				server.waitForAllItemsReceived());
		LOGGER.info("All items received: {}", numberOfItems);

		assertThat(server.getItemsSnapshot(), everyItem(equalTo(expectedValue)));
		assertEquals(numberOfItems, server.getItemsSnapshot().size());
	}

	private NettyStatsDClientImpl newClient() {
		return new NettyStatsDClientImpl("127.0.0.1", PORT, flushProbability);
	}

	private UDPServer newServer(int numberOfItems) {
		try {
			return serverClass.getConstructor(int.class, int.class, int.class)
					.newInstance(PORT, numberOfItems, recvbufValue);
		} catch (InstantiationException | IllegalAccessException
				| IllegalArgumentException | InvocationTargetException
				| NoSuchMethodException | SecurityException e) {
			throw new RuntimeException(e);
		}
	}
}
