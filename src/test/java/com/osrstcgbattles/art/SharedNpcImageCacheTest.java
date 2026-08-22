package com.osrstcgbattles.art;

import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import okhttp3.OkHttpClient;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

public class SharedNpcImageCacheTest
{
	@Test
	public void completesOnEdtWhenNotStarted() throws Exception
	{
		SharedNpcImageCache cache = new SharedNpcImageCache(mock(OkHttpClient.class));
		CountDownLatch called = new CountDownLatch(1);
		CountDownLatch edtBlocked = new CountDownLatch(1);
		CountDownLatch releaseEdt = new CountDownLatch(1);
		SwingUtilities.invokeLater(() ->
		{
			edtBlocked.countDown();
			try
			{
				releaseEdt.await(5, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex)
			{
				throw new AssertionError(ex);
			}
		});
		assertTrue(edtBlocked.await(5, TimeUnit.SECONDS));

		cache.get("https://example.com/not-started.png", image ->
		{
			assertNull(image);
			assertTrue(SwingUtilities.isEventDispatchThread());
			called.countDown();
		});
		cache.start();
		releaseEdt.countDown();

		assertTrue(called.await(5, TimeUnit.SECONDS));
		cache.dispose();
	}

	@Test
	public void completesPendingConsumersOnEdtWhenSubmissionIsRejected() throws Exception
	{
		SharedNpcImageCache cache = new SharedNpcImageCache(mock(OkHttpClient.class), RejectingExecutor::new);
		CountDownLatch called = new CountDownLatch(2);
		cache.start();

		cache.get("https://example.com/rejected.png", image -> assertEdtNull(image, called));
		cache.get("https://example.com/rejected.png", image -> assertEdtNull(image, called));

		assertTrue(called.await(5, TimeUnit.SECONDS));
	}

	@Test
	public void disposalInvalidatesQueuedCallbacks() throws Exception
	{
		QueuedExecutor executor = new QueuedExecutor();
		SharedNpcImageCache cache = new SharedNpcImageCache(mock(OkHttpClient.class), () -> executor);
		CountDownLatch called = new CountDownLatch(1);
		cache.start();
		cache.get("https://example.com/disposed.png", image -> called.countDown());

		cache.dispose();
		executor.task.run();
		SwingUtilities.invokeAndWait(() -> { });

		assertEquals(1, called.getCount());
	}

	private static void assertEdtNull(BufferedImage image, CountDownLatch called)
	{
		assertNull(image);
		assertTrue(SwingUtilities.isEventDispatchThread());
		called.countDown();
	}

	private static class RejectingExecutor extends AbstractExecutorService
	{
		@Override public void shutdown() { }
		@Override public List<Runnable> shutdownNow() { return Collections.emptyList(); }
		@Override public boolean isShutdown() { return false; }
		@Override public boolean isTerminated() { return false; }
		@Override public boolean awaitTermination(long timeout, TimeUnit unit) { return false; }
		@Override public void execute(Runnable command) { throw new RejectedExecutionException(); }
	}

	private static final class QueuedExecutor extends RejectingExecutor
	{
		private Runnable task;

		@Override
		public void execute(Runnable command)
		{
			task = command;
		}
	}
}
