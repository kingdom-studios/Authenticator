package pl.kingdomcraft.authenticator.common.database;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;

import org.junit.jupiter.api.Test;

class SqlTest {

	@Test
	void ipv4IsWrittenIpv4MappedBecauseInet6RejectsPlainIpv4() throws Exception {
		assertEquals("::ffff:1.2.3.4", Sql.ipText(InetAddress.getByName("1.2.3.4")));
		assertEquals("::ffff:255.0.10.200", Sql.ipText(InetAddress.getByName("255.0.10.200")));
	}

	@Test
	void ipv6KeepsItsAddressAndDropsTheScope() throws Exception {
		assertEquals(InetAddress.getByName("2001:db8::1"), InetAddress.getByName(Sql.ipText(InetAddress.getByName("2001:db8::1"))));
		assertEquals(InetAddress.getByName("fe80::1"), InetAddress.getByName(Sql.ipText(InetAddress.getByName("fe80::1%1"))));
	}
}
