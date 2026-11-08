/*
 * Copyright 2002-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.server.session;

import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import org.springframework.beans.DirectFieldAccessor;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.server.WebSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * Unit tests for {@link InMemoryWebSessionStore}.
 * @author Rob Winch
 */
public class InMemoryWebSessionStoreTests {

	private InMemoryWebSessionStore store = new InMemoryWebSessionStore();


	@Test
	public void startsSessionExplicitly() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		assertThat(session.isStarted()).isTrue();
	}

	@Test
	public void startsSessionImplicitly() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		session.getAttributes().put("foo", "bar");
		assertThat(session.isStarted()).isTrue();
	}

	@Disabled // TODO: remove if/when Blockhound is enabled
	@Test // gh-24027
	public void createSessionDoesNotBlock() {
		Mono.defer(() -> this.store.createWebSession())
				.subscribeOn(Schedulers.parallel())
				.block();
	}

	@Test
	public void retrieveExpiredSession() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.getAttributes().put("foo", "bar");
		session.save().block();

		String id = session.getId();
		WebSession retrieved = this.store.retrieveSession(id).block();
		assertThat(retrieved).isNotNull();
		assertThat(retrieved).isSameAs(session);

		// Fast-forward 31 minutes
		this.store.setClock(Clock.offset(this.store.getClock(), Duration.ofMinutes(31)));
		WebSession retrievedAgain = this.store.retrieveSession(id).block();
		assertThat(retrievedAgain).isNull();
	}

	@Test
	public void lastAccessTimeIsUpdatedOnRetrieve() {
		WebSession session1 = this.store.createWebSession().block();
		assertThat(session1).isNotNull();
		String id = session1.getId();
		Instant time1 = session1.getLastAccessTime();
		session1.start();
		session1.save().block();

		// Fast-forward a few seconds
		this.store.setClock(Clock.offset(this.store.getClock(), Duration.ofSeconds(5)));

		WebSession session2 = this.store.retrieveSession(id).block();
		assertThat(session2).isNotNull();
		assertThat(session2).isSameAs(session1);
		Instant time2 = session2.getLastAccessTime();
		assertThat(time1.isBefore(time2)).isTrue();
	}

	@Test // SPR-17051
	public void sessionInvalidatedBeforeSave() {
		// Request 1 creates session
		WebSession session1 = this.store.createWebSession().block();
		assertThat(session1).isNotNull();
		String id = session1.getId();
		session1.start();
		session1.save().block();

		// Request 2 retrieves session
		WebSession session2 = this.store.retrieveSession(id).block();
		assertThat(session2).isNotNull();
		assertThat(session2).isSameAs(session1);

		// Request 3 retrieves and invalidates
		WebSession session3 = this.store.retrieveSession(id).block();
		assertThat(session3).isNotNull();
		assertThat(session3).isSameAs(session1);
		session3.invalidate().block();

		// Request 2 saves session after invalidated
		session2.save().block();

		// Session should not be present
		WebSession session4 = this.store.retrieveSession(id).block();
		assertThat(session4).isNull();
	}

	@Test
	public void expirationCheckPeriod() {

		DirectFieldAccessor accessor = new DirectFieldAccessor(this.store);
		Map<?,?> sessions = (Map<?, ?>) accessor.getPropertyValue("sessions");
		assertThat(sessions).isNotNull();

		// Create 100 sessions
		IntStream.range(0, 100).forEach(i -> insertSession());
		assertThat(sessions.size()).isEqualTo(100);

		// Force a new clock (31 min later), don't use setter which would clean expired sessions
		accessor.setPropertyValue("clock", Clock.offset(this.store.getClock(), Duration.ofMinutes(31)));
		assertThat(sessions.size()).isEqualTo(100);

		// Create 1 more which forces a time-based check (clock moved forward)
		insertSession();
		assertThat(sessions.size()).isEqualTo(1);
	}

	@Test
	public void maxSessions() {

		IntStream.range(0, 10000).forEach(i -> insertSession());
		assertThatIllegalStateException().isThrownBy(
				this::insertSession)
			.withMessage("Max sessions limit reached: 10000");
	}

	@Test // gh-35121
	public void changeSessionIdRemovesOldIdFromStore() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		session.save().block();

		String oldId = session.getId();
		assertThat(this.store.getSessions()).containsOnlyKeys(oldId);

		session.changeSessionId().block();

		String newId = session.getId();
		assertThat(newId).isNotEqualTo(oldId);
		assertThat(this.store.getSessions()).containsOnlyKeys(newId);
		assertThat(this.store.getSessions().get(newId)).isSameAs(session);
		assertThat(this.store.retrieveSession(oldId).block()).isNull();
		assertThat(this.store.retrieveSession(newId).block()).isSameAs(session);
	}

	@Test // gh-35121
	public void saveAfterChangeSessionIdDoesNotRestoreOldId() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		session.save().block();

		String oldId = session.getId();
		session.changeSessionId().block();
		String newId = session.getId();

		// A save triggered after the id changed must not re-register the old id
		session.save().block();

		assertThat(this.store.getSessions()).containsOnlyKeys(newId);
		assertThat(this.store.retrieveSession(oldId).block()).isNull();
	}

	@Test // gh-35121
	public void saveIsIdempotent() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();

		session.save().block();
		session.save().block();
		session.save().block();

		assertThat(session.isStarted()).isTrue();
		assertThat(this.store.getSessions()).containsOnlyKeys(session.getId());
		assertThat(this.store.retrieveSession(session.getId()).block()).isSameAs(session);
	}

	@Test // gh-35121
	public void saveAfterInvalidateFails() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		session.save().block();
		String id = session.getId();

		session.invalidate().block();
		assertThat(this.store.getSessions()).doesNotContainKey(id);

		// Re-populate attributes so that the session is considered started again
		session.getAttributes().put("foo", "bar");

		assertThatIllegalStateException()
				.isThrownBy(() -> session.save().block())
				.withMessage("Session was invalidated");

		assertThat(this.store.getSessions()).doesNotContainKey(id);
	}

	@Test // gh-35121, session fixation
	public void concurrentSaveDoesNotResurrectOldSessionId() throws Exception {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();

		// Instrument the session map so that the very first insert can be suspended
		// at the exact point where the session is about to be registered under the
		// id that the saving thread read.
		TrappingSessionMap sessions = new TrappingSessionMap();
		replaceSessionsMap(this.store, sessions);

		String oldId = session.getId();
		AtomicReference<Throwable> failure = new AtomicReference<>();

		// Request 1: saves the session and gets suspended mid-insert.
		Thread saving = newThread("save", () -> session.save().block(), failure);
		saving.start();
		assertThat(sessions.trapped.await(5, TimeUnit.SECONDS)).isTrue();

		// Request 2: concurrently rotates the session id, e.g. right after authentication.
		Thread changing = newThread("changeSessionId", () -> session.changeSessionId().block(), failure);
		changing.start();

		// Give request 2 the chance to complete first, then release request 1. Without
		// the lock, request 1 completes its insert with the id it read before the
		// rotation and the pre-authentication id becomes usable again.
		sessions.removed.await(1, TimeUnit.SECONDS);
		sessions.release.countDown();

		saving.join(10_000);
		changing.join(10_000);
		assertThat(saving.isAlive()).isFalse();
		assertThat(changing.isAlive()).isFalse();
		assertThat(failure.get()).isNull();

		String newId = session.getId();
		assertThat(newId).isNotEqualTo(oldId);
		assertThat(this.store.getSessions()).containsOnlyKeys(newId);
		assertThat(this.store.retrieveSession(oldId).block()).isNull();
		assertThat(this.store.retrieveSession(newId).block()).isSameAs(session);
	}

	private static Thread newThread(String name, Runnable runnable, AtomicReference<Throwable> failure) {
		Thread thread = new Thread(() -> {
			try {
				runnable.run();
			}
			catch (Throwable ex) {
				failure.compareAndSet(null, ex);
			}
		}, name);
		thread.setDaemon(true);
		return thread;
	}

	private static void replaceSessionsMap(InMemoryWebSessionStore store, Map<String, Object> map) {
		Field field = ReflectionUtils.findField(InMemoryWebSessionStore.class, "sessions");
		assertThat(field).isNotNull();
		ReflectionUtils.makeAccessible(field);
		ReflectionUtils.setField(field, store, map);
	}

	private WebSession insertSession() {
		WebSession session = this.store.createWebSession().block();
		assertThat(session).isNotNull();
		session.start();
		session.save().block();
		return session;
	}


	/**
	 * Session map that suspends the first insert until released, and signals when
	 * an entry is removed. This makes the save/changeSessionId interleaving that
	 * leads to session fixation reproducible.
	 */
	@SuppressWarnings("serial")
	private static class TrappingSessionMap extends ConcurrentHashMap<String, Object> {

		final CountDownLatch trapped = new CountDownLatch(1);

		final CountDownLatch release = new CountDownLatch(1);

		final CountDownLatch removed = new CountDownLatch(1);

		private final AtomicBoolean armed = new AtomicBoolean(true);

		@Override
		public Object put(String key, Object value) {
			suspendFirstInsert();
			return super.put(key, value);
		}

		@Override
		public Object putIfAbsent(String key, Object value) {
			suspendFirstInsert();
			return super.putIfAbsent(key, value);
		}

		@Override
		public Object remove(Object key) {
			Object previous = super.remove(key);
			this.removed.countDown();
			return previous;
		}

		private void suspendFirstInsert() {
			if (this.armed.compareAndSet(true, false)) {
				this.trapped.countDown();
				try {
					this.release.await(10, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
		}
	}

}
