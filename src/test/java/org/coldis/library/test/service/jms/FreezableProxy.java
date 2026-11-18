package org.coldis.library.test.service.jms;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * TCP proxy to a broker that can freeze: its connections stay open and nothing
 * goes through them, as with a broker process that stopped.
 */
public final class FreezableProxy implements AutoCloseable {

	/**
	 * Listening socket.
	 */
	private final ServerSocket server;

	/**
	 * Broker port.
	 */
	private final int brokerPort;

	/**
	 * Open sockets.
	 */
	private final List<Socket> sockets = new CopyOnWriteArrayList<>();

	/**
	 * Whether data stops going through.
	 */
	private volatile boolean frozen;

	/**
	 * Constructor.
	 *
	 * @param  brokerPort  Broker port.
	 * @throws IOException If it cannot listen.
	 */
	public FreezableProxy(final int brokerPort) throws IOException {
		this.brokerPort = brokerPort;
		this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
		Thread.ofPlatform().daemon().name("freezable-proxy").start(this::accept);
	}

	/**
	 * Gets the port to connect to.
	 *
	 * @return The port.
	 */
	public int getPort() {
		return this.server.getLocalPort();
	}

	/**
	 * Stops all data, keeping the connections open.
	 */
	public void freeze() {
		this.frozen = true;
	}

	/**
	 * Lets data through again.
	 */
	public void thaw() {
		this.frozen = false;
	}

	/**
	 * Accepts connections and pipes each to the broker.
	 */
	private void accept() {
		while (!this.server.isClosed()) {
			try {
				final Socket client = this.server.accept();
				final Socket broker = new Socket(InetAddress.getLoopbackAddress(), this.brokerPort);
				this.sockets.add(client);
				this.sockets.add(broker);
				this.pipe(client, broker);
				this.pipe(broker, client);
			}
			catch (final IOException exception) {
				// Closed.
			}
		}
	}

	/**
	 * Copies data from one socket to another while not frozen.
	 *
	 * @param from Source.
	 * @param to   Destination.
	 */
	private void pipe(
			final Socket from,
			final Socket to) {
		Thread.ofPlatform().daemon().name("freezable-proxy-pipe").start(() -> {
			final byte[] buffer = new byte[8192];
			try (InputStream input = from.getInputStream(); OutputStream output = to.getOutputStream()) {
				int read = input.read(buffer);
				while (read >= 0) {
					while (this.frozen && !from.isClosed()) {
						Thread.sleep(100);
					}
					output.write(buffer, 0, read);
					output.flush();
					read = input.read(buffer);
				}
			}
			catch (final IOException | InterruptedException exception) {
				// Closed.
			}
		});
	}

	/**
	 * Closes the proxy and its connections.
	 */
	@Override
	public void close() throws IOException {
		this.server.close();
		for (final Socket socket : this.sockets) {
			socket.close();
		}
	}

}
