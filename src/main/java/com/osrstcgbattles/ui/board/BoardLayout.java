package com.osrstcgbattles.ui.board;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;

/**
 * Lays a container's children out as a single horizontal run, evenly spaced by a fixed gap
 * and centered both horizontally and vertically within the container. Used for the board's
 * row bands and could be reused anywhere else a centered card run is needed.
 */
public class BoardLayout implements LayoutManager
{
	private final int gap;

	public BoardLayout(int gap)
	{
		this.gap = gap;
	}

	@Override
	public void addLayoutComponent(String name, Component comp)
	{
		// No per-child bookkeeping is needed; children are read straight off the container.
	}

	@Override
	public void removeLayoutComponent(Component comp)
	{
		// No per-child bookkeeping is needed; children are read straight off the container.
	}

	@Override
	public Dimension preferredLayoutSize(Container parent)
	{
		synchronized (parent.getTreeLock())
		{
			Insets insets = parent.getInsets();
			int count = parent.getComponentCount();
			int width = 0;
			int height = 0;
			for (int i = 0; i < count; i++)
			{
				Dimension size = parent.getComponent(i).getPreferredSize();
				width += size.width;
				if (i > 0)
				{
					width += gap;
				}
				height = Math.max(height, size.height);
			}
			return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
		}
	}

	@Override
	public Dimension minimumLayoutSize(Container parent)
	{
		return preferredLayoutSize(parent);
	}

	@Override
	public void layoutContainer(Container parent)
	{
		synchronized (parent.getTreeLock())
		{
			int count = parent.getComponentCount();
			if (count == 0)
			{
				return;
			}

			Insets insets = parent.getInsets();
			int availableWidth = parent.getWidth() - insets.left - insets.right;
			int availableHeight = parent.getHeight() - insets.top - insets.bottom;
			Dimension preferred = parent.getComponent(0).getPreferredSize();
			int actualGap = Math.min(gap, Math.max(2, availableWidth / Math.max(1, count * 10)));
			int cardWidth = Math.min(preferred.width,
				Math.max(18, (availableWidth - actualGap * (count - 1)) / count));
			int cardHeight = Math.min(preferred.height,
				Math.max(24, cardWidth * preferred.height / Math.max(1, preferred.width)));
			if (cardHeight > availableHeight)
			{
				cardHeight = Math.max(24, availableHeight);
				cardWidth = Math.max(18, cardHeight * preferred.width / Math.max(1, preferred.height));
			}
			int totalWidth = cardWidth * count + actualGap * (count - 1);
			int x = Math.max(insets.left, insets.left + (availableWidth - totalWidth) / 2);

			for (int i = 0; i < count; i++)
			{
				Component child = parent.getComponent(i);
				int y = Math.max(insets.top, insets.top + (availableHeight - cardHeight) / 2);
				child.setBounds(x, y, cardWidth, cardHeight);
				x += cardWidth + actualGap;
			}
		}
	}
}
