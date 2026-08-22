package com.theovit;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import javax.inject.Inject;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.components.TextComponent;

class BankedCountOverlay extends WidgetItemOverlay
{
	private static final Font BASE_FONT = FontManager.getRunescapeBoldFont();

	private final ItemManager itemManager;
	private final BankedCountPlugin plugin;
	private final BankedCountConfig config;

	@Inject
	BankedCountOverlay(ItemManager itemManager, BankedCountPlugin plugin, BankedCountConfig config)
	{
		this.itemManager = itemManager;
		this.plugin = plugin;
		this.config = config;
		showOnInventory();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		int canonicalId = itemManager.canonicalize(itemId);

		Integer bankCount = plugin.getBankQuantity(canonicalId);
		if (bankCount == null)
		{
			return;
		}

		String name = itemManager.getItemComposition(canonicalId).getName();
		if (plugin.isExcluded(name))
		{
			return;
		}

		String text = formatCount(bankCount);

		Font font = BASE_FONT.deriveFont((float) config.fontSize());

		Rectangle bounds = widgetItem.getCanvasBounds();
		FontMetrics metrics = graphics.getFontMetrics(font);
		Point position = textPosition(config.overlayPosition(), bounds, metrics, text);

		TextComponent textComponent = new TextComponent();
		textComponent.setPosition(position);
		textComponent.setColor(config.textColor());
		textComponent.setFont(font);
		textComponent.setOutline(true);
		textComponent.setText(text);
		textComponent.render(graphics);
	}

	private String formatCount(int quantity)
	{
		if (quantity < 1000 || !config.shorthandNumbers())
		{
			return Integer.toString(quantity);
		}
		if (quantity < 1_000_000)
		{
			return formatShort(quantity, 1_000, "K");
		}
		if (quantity < 1_000_000_000)
		{
			return formatShort(quantity, 1_000_000, "M");
		}
		return formatShort(quantity, 1_000_000_000, "B");
	}

	private static String formatShort(int quantity, int divisor, String suffix)
	{
		long tenths = (quantity * 10L) / divisor;
		return (tenths / 10) + "." + (tenths % 10) + suffix;
	}

	private static Point textPosition(OverlayCorner corner, Rectangle bounds, FontMetrics metrics, String text)
	{
		boolean left = corner == OverlayCorner.TOP_LEFT || corner == OverlayCorner.BOTTOM_LEFT;
		boolean top = corner == OverlayCorner.TOP_LEFT || corner == OverlayCorner.TOP_RIGHT;

		int x = left ? bounds.x + 1 : bounds.x + bounds.width - metrics.stringWidth(text) - 1;
		int y = top ? bounds.y + metrics.getAscent() + 1 : bounds.y + bounds.height - 2;

		return new Point(x, y);
	}
}
