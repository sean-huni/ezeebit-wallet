package io.ezeebit.ledger;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

// The money endpoints are unauthenticated, so locally the ledger answers on loopback only, never on the LAN.
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("acceptance")
class LoopbackOnlyIT {

	private static final int CONNECT_TIMEOUT_MILLIS = 1_000;

	@LocalServerPort
	private int port;

	@Value("${local.grpc.server.port}")
	private int grpcPort;

	@Test
	void theLedgerAnswersOnLoopback() {
		assertThatCode(() -> connect(InetAddress.getLoopbackAddress(), port)).doesNotThrowAnyException();
	}

	@Test
	void theLedgerRefusesConnectionsOnTheLanAddress() throws SocketException {
		Optional<InetAddress> lan = lanAddress();
		assumeTrue(lan.isPresent(), "this host has no LAN address to probe");

		assertThatThrownBy(() -> connect(lan.get(), port)).isInstanceOf(IOException.class);
	}

	@Test
	void theGrpcServerAnswersOnLoopback() {
		assertThatCode(() -> connect(InetAddress.getLoopbackAddress(), grpcPort)).doesNotThrowAnyException();
	}

	@Test
	void theGrpcServerRefusesConnectionsOnTheLanAddress() throws SocketException {
		Optional<InetAddress> lan = lanAddress();
		assumeTrue(lan.isPresent(), "this host has no LAN address to probe");

		assertThatThrownBy(() -> connect(lan.get(), grpcPort)).isInstanceOf(IOException.class);
	}

	private static void connect(final InetAddress address, final int target) throws IOException {
		try (Socket socket = new Socket()) {
			socket.connect(new InetSocketAddress(address, target), CONNECT_TIMEOUT_MILLIS);
		}
	}

	private static Optional<InetAddress> lanAddress() throws SocketException {
		return NetworkInterface.networkInterfaces().filter(LoopbackOnlyIT::up)
				.flatMap(NetworkInterface::inetAddresses)
				.filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress()
						&& !address.isLinkLocalAddress())
				.findFirst();
	}

	private static boolean up(final NetworkInterface candidate) {
		try {
			return candidate.isUp() && !candidate.isLoopback();
		}
		catch (SocketException ex) {
			return false;
		}
	}

}
