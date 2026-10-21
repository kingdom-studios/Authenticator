package pl.kingdomcraft.authenticator.common.hash;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import pl.kingdomcraft.authenticator.common.hash.PasswordHasher.Hashed;
import pl.kingdomcraft.authenticator.common.hash.PasswordHasher.Settings;

/**
 * Verification is checked against hashes made by independent implementations (Python's bcrypt, argon2-cffi, hashlib), the same way
 * mineLogin made them, so that accounts imported from it can log in.
 */
class PasswordHasherTest {

	private static final String PASSWORD = "hunter2";
	private static final String SALT = "Zk3pQ9";

	private static final String BCRYPT_2B = "$2b$06$rjGfgeL9RvskvOZYptY0lOYBluwW0p9wRX2YFfoNvYXfPUJNcWKcC";
	private static final String BCRYPT_2B_SALTED = "$2b$06$G1sK7wAK5B3lCaPUpfFS6O6CMqczh/4uYUqXHHJuWe9KYmBAOUaOO";
	private static final String BCRYPT_UNICODE = "$2b$06$swMFFNJvBp.EuVLAj2o2mOxo2ocaBsBaYqqDX..8YRWT287cnGlV6";
	private static final String ARGON2I_MINELOGIN = "$argon2i$v=19$m=65536,t=4,p=1$xPKVM7H9iYoqTphzkvY5OA$4eQWkIIiUraWR7y2qsCfPvedHP5LdSn5WVxtsrZRjNQ";
	private static final String ARGON2ID_WEAK = "$argon2id$v=19$m=8,t=1,p=1$fFKyFG5HpuiGFCmPKEvjBA$02VGr0IqCgC3ZvJeRu/Kpn4gXqKqD7qF0sAN5GExzqg";
	private static final String ARGON2ID_STRONG = "$argon2id$v=19$m=19456,t=2,p=1$a4Wm3nBIvXyNtdJyf1HDYw$TZ610NcQjIrOAnuiQStTHLdTjPU3m+3oCV75vlmegqs";
	private static final String ARGON2D = "$argon2d$v=19$m=1024,t=2,p=2$daIfyrFCQYRJOGo/kZLEbQ$gRZ2yViP+acSv0AT69ak1gmtrQSX3vK5LfmFR5xGRu8";
	private static final String ARGON2I_SALTED = "$argon2i$v=19$m=1024,t=2,p=1$NJQXH99HgkZdU3o8hpp/mQ$zcUBpbVDbi9RoTdNSQM/iOwzgnMr7mPXZKiJ+BhYhB0";
	private static final String SHA256_PLAIN = "f52fbd32b2b3b86ff88ef6c490628285f482af15ddcb29541f94bcf526a3f6c7";
	private static final String SHA256_SALTED = "fcf57a58b2120bbde39ee8cf358ae073c3305f0fb655eabc5068ab70213a2f55";
	private static final String SHA512_SALTED = "5e85320a1618166bf47ec7c1aa8dad4597818fa8ebce6f1f7170d78fff16ea135327a5e4e7b05004d96004a6807a6d2f1a52b57bf411d162a6a3d0e1386f93cf";
	private static final String SHA256_UNICODE = "3e1e125caff289eb649d40f0efd3373b6ad43a3f673d5149e9604bf1bd9add3e";

	private static Settings bcrypt(int cost) {
		return new Settings(HashAlgo.BCRYPT, cost, 19_456, 2, 1);
	}

	private static Settings argon2id(int memory, int iterations, int parallelism) {
		return new Settings(HashAlgo.ARGON2ID, 12, memory, iterations, parallelism);
	}

	private final PasswordHasher hasher = new PasswordHasher(bcrypt(6));

	// ---- verifying hashes made by other implementations ----

	@Test
	void verifiesBcryptFromAnotherImplementation() {
		assertTrue(this.hasher.verify(HashAlgo.BCRYPT, BCRYPT_2B, null, PASSWORD));
		assertFalse(this.hasher.verify(HashAlgo.BCRYPT, BCRYPT_2B, null, "hunter3"));
		assertFalse(this.hasher.verify(HashAlgo.BCRYPT, BCRYPT_2B, null, ""));
	}

	@Test
	void everyBcryptRevisionMinelogin_AcceptsIsAccepted() {
		for (final String revision : new String[] { "2a", "2b", "2y" })
			assertTrue(this.hasher.verify(HashAlgo.BCRYPT, "$" + revision + BCRYPT_2B.substring(3), null, PASSWORD), revision);
	}

	@Test
	void theSaltIsAppendedToThePasswordLikeMinelogin() {
		assertTrue(this.hasher.verify(HashAlgo.BCRYPT, BCRYPT_2B_SALTED, SALT, PASSWORD));
		assertFalse(this.hasher.verify(HashAlgo.BCRYPT, BCRYPT_2B_SALTED, null, PASSWORD), "without the salt it must not match");
		assertFalse(this.hasher.verify(HashAlgo.BCRYPT, BCRYPT_2B_SALTED, "other", PASSWORD));
		assertTrue(this.hasher.verify(HashAlgo.ARGON2, ARGON2I_SALTED, SALT, PASSWORD));
		assertTrue(this.hasher.verify(HashAlgo.SHA256, SHA256_SALTED, SALT, PASSWORD));
		assertTrue(this.hasher.verify(HashAlgo.SHA512, SHA512_SALTED, SALT, PASSWORD));
		assertFalse(this.hasher.verify(HashAlgo.SHA256, SHA256_SALTED, null, PASSWORD));
	}

	@Test
	void anEmptySaltIsTheSameAsNone() {
		assertTrue(this.hasher.verify(HashAlgo.SHA256, SHA256_PLAIN, "", PASSWORD));
		assertTrue(this.hasher.verify(HashAlgo.SHA256, SHA256_PLAIN, null, PASSWORD));
	}

	@Test
	void verifiesEveryArgon2Variant() {
		assertTrue(this.hasher.verify(HashAlgo.ARGON2, ARGON2I_MINELOGIN, null, PASSWORD), "argon2i as mineLogin writes it");
		assertTrue(this.hasher.verify(HashAlgo.ARGON2, ARGON2D, null, PASSWORD));
		assertTrue(this.hasher.verify(HashAlgo.ARGON2ID, ARGON2ID_WEAK, null, PASSWORD));
		assertTrue(this.hasher.verify(HashAlgo.ARGON2ID, ARGON2ID_STRONG, null, PASSWORD));
		assertFalse(this.hasher.verify(HashAlgo.ARGON2, ARGON2I_MINELOGIN, null, "hunter3"));
		assertFalse(this.hasher.verify(HashAlgo.ARGON2ID, ARGON2ID_WEAK, null, "hunter3"));
	}

	@Test
	void digestsCompareHexCaseInsensitivelyAndAsUtf8() {
		assertTrue(this.hasher.verify(HashAlgo.SHA256, SHA256_PLAIN.toUpperCase(), null, PASSWORD));
		assertTrue(this.hasher.verify(HashAlgo.SHA256, SHA256_UNICODE, null, "zażółć-gęślą"));
		assertTrue(this.hasher.verify(HashAlgo.BCRYPT, BCRYPT_UNICODE, null, "zażółć-gęślą"));
		assertFalse(this.hasher.verify(HashAlgo.BCRYPT, BCRYPT_UNICODE, null, "zazolc-gesla"));
	}

	@Test
	void anUnreadableStoredValueNeverMatchesAndNeverThrows() {
		for (final HashAlgo algo : HashAlgo.values())
			for (final String broken : new String[] { "", "garbage", "$2b$", "$argon2id$", "zz".repeat(32), "$2b$99$short", "\u0000" })
				assertFalse(this.hasher.verify(algo, broken, null, PASSWORD), algo + " / " + broken);

		assertFalse(this.hasher.verify(null, BCRYPT_2B, null, PASSWORD));
		assertFalse(this.hasher.verify(HashAlgo.BCRYPT, null, null, PASSWORD));
		assertFalse(this.hasher.verify(HashAlgo.SHA256, BCRYPT_2B, null, PASSWORD), "a hash of one kind must not verify as another");
		assertFalse(this.hasher.verify(HashAlgo.BCRYPT, SHA256_PLAIN, null, PASSWORD));
	}

	// ---- new hashes ----

	@Test
	void newBcryptHashesVerifyAndAreWrittenAsTwoAForMinelogin() {
		final Hashed hashed = this.hasher.hash(PASSWORD);

		assertEquals(HashAlgo.BCRYPT, hashed.algorithm());
		assertTrue(hashed.hash().startsWith("$2a$06$"), hashed.hash());
		assertTrue(this.hasher.verify(hashed.algorithm(), hashed.hash(), null, PASSWORD));
		assertFalse(this.hasher.verify(hashed.algorithm(), hashed.hash(), null, "wrong"));
	}

	@Test
	void newHashesOfTheSamePasswordDiffer() {
		assertNotEquals(this.hasher.hash(PASSWORD).hash(), this.hasher.hash(PASSWORD).hash());
	}

	@Test
	void newArgon2idHashesVerifyAndCarryTheirParameters() {
		final PasswordHasher argon = new PasswordHasher(argon2id(1024, 2, 1));
		final Hashed hashed = argon.hash(PASSWORD);

		assertEquals(HashAlgo.ARGON2ID, hashed.algorithm());
		assertTrue(hashed.hash().startsWith("$argon2id$v=19$m=1024,t=2,p=1$"), hashed.hash());
		assertTrue(argon.verify(HashAlgo.ARGON2ID, hashed.hash(), null, PASSWORD));
		assertFalse(argon.verify(HashAlgo.ARGON2ID, hashed.hash(), null, "wrong"));
	}

	@Test
	void anArgon2HashFromAnotherImplementationIsAcceptedByTheHasherThatWritesIt() {
		assertTrue(new PasswordHasher(argon2id(19_456, 2, 1)).verify(HashAlgo.ARGON2ID, ARGON2ID_STRONG, null, PASSWORD));
	}

	// ---- rehash ----

	@Test
	void legacyAlgorithmsAreAlwaysReplaced() {
		for (final PasswordHasher h : new PasswordHasher[] { new PasswordHasher(bcrypt(6)), new PasswordHasher(argon2id(1024, 2, 1)) }) {
			assertTrue(h.needsRehash(HashAlgo.SHA256, SHA256_PLAIN));
			assertTrue(h.needsRehash(HashAlgo.SHA512, SHA512_SALTED));
			assertTrue(h.needsRehash(HashAlgo.ARGON2, ARGON2I_MINELOGIN), "argon2i from mineLogin");
		}
	}

	@Test
	void aWeakerBcryptCostIsReplacedButAnEqualOrStrongerOneIsKept() {
		assertFalse(new PasswordHasher(bcrypt(6)).needsRehash(HashAlgo.BCRYPT, BCRYPT_2B));
		assertFalse(new PasswordHasher(bcrypt(4)).needsRehash(HashAlgo.BCRYPT, BCRYPT_2B));
		assertTrue(new PasswordHasher(bcrypt(7)).needsRehash(HashAlgo.BCRYPT, BCRYPT_2B));
	}

	@Test
	void aWeakerArgon2idIsReplacedButAnEqualOrStrongerOneIsKept() {
		assertTrue(new PasswordHasher(argon2id(19_456, 2, 1)).needsRehash(HashAlgo.ARGON2ID, ARGON2ID_WEAK));
		assertFalse(new PasswordHasher(argon2id(19_456, 2, 1)).needsRehash(HashAlgo.ARGON2ID, ARGON2ID_STRONG));
		assertFalse(new PasswordHasher(argon2id(8, 1, 1)).needsRehash(HashAlgo.ARGON2ID, ARGON2ID_STRONG));
		assertTrue(new PasswordHasher(argon2id(19_456, 3, 1)).needsRehash(HashAlgo.ARGON2ID, ARGON2ID_STRONG), "fewer iterations than configured");
		assertTrue(new PasswordHasher(argon2id(19_456, 2, 2)).needsRehash(HashAlgo.ARGON2ID, ARGON2ID_STRONG), "less parallelism than configured");
	}

	@Test
	void changingTheConfiguredAlgorithmReplacesTheOldOne() {
		assertTrue(new PasswordHasher(argon2id(1024, 2, 1)).needsRehash(HashAlgo.BCRYPT, BCRYPT_2B));
		assertTrue(new PasswordHasher(bcrypt(6)).needsRehash(HashAlgo.ARGON2ID, ARGON2ID_WEAK));
	}

	@Test
	void aHashThatCannotBeParsedIsReplaced() {
		assertTrue(this.hasher.needsRehash(HashAlgo.BCRYPT, "garbage"));
		assertTrue(this.hasher.needsRehash(HashAlgo.ARGON2ID, "garbage"));
	}

	// ---- settings and detection ----

	@Test
	void settingsRefuseAlgorithmsThatCannotBeWritten() {
		assertThrows(IllegalArgumentException.class, () -> new Settings(HashAlgo.SHA256, 12, 19_456, 2, 1));
		assertThrows(IllegalArgumentException.class, () -> new Settings(HashAlgo.ARGON2, 12, 19_456, 2, 1));
		assertThrows(IllegalArgumentException.class, () -> bcrypt(3));
		assertThrows(IllegalArgumentException.class, () -> bcrypt(17));
		assertThrows(IllegalArgumentException.class, () -> argon2id(4, 2, 1));
		assertThrows(IllegalArgumentException.class, () -> argon2id(19_456, 0, 1));
		assertThrows(IllegalArgumentException.class, () -> argon2id(19_456, 2, 0));
	}

	@Test
	void theDefaultsAreBcryptCostTwelveLikeMinelogin() {
		assertEquals(HashAlgo.BCRYPT, Settings.defaults().algorithm());
		assertEquals(12, Settings.defaults().bcryptCost());
	}

	@Test
	void detectsTheAlgorithmFromTheShapeOfAHash() {
		assertEquals(HashAlgo.BCRYPT, HashAlgo.detect(BCRYPT_2B));
		assertEquals(HashAlgo.BCRYPT, HashAlgo.detect("$2y$10$abc"));
		assertEquals(HashAlgo.ARGON2, HashAlgo.detect(ARGON2I_MINELOGIN));
		assertEquals(HashAlgo.ARGON2, HashAlgo.detect(ARGON2D));
		assertEquals(HashAlgo.ARGON2ID, HashAlgo.detect(ARGON2ID_STRONG));
		assertEquals(HashAlgo.SHA256, HashAlgo.detect(SHA256_PLAIN));
		assertEquals(HashAlgo.SHA512, HashAlgo.detect(SHA512_SALTED));
		assertNull(HashAlgo.detect("5f4dcc3b5aa765d61d8327deb882cf99"), "MD5 is not supported");
		assertNull(HashAlgo.detect("$SHA$salt$" + "a".repeat(64)), "AuthMe formats are not supported");
		assertNull(HashAlgo.detect(""));
		assertNull(HashAlgo.detect(null));
	}

	@Test
	void parsesAlgorithmNamesFromTheDatabaseAndFromMinelogin() {
		assertEquals(HashAlgo.BCRYPT, HashAlgo.parse("BCRYPT"));
		assertEquals(HashAlgo.SHA256, HashAlgo.parse(" sha256 "));
		assertEquals(HashAlgo.ARGON2, HashAlgo.parse("ARGON2"));
		assertNull(HashAlgo.parse("MD5"));
		assertNull(HashAlgo.parse("AUTHME_SHA256"));
		assertNull(HashAlgo.parse(null));
	}
}
