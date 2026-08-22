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
		int x = bounds.x + bounds.width - metrics.stringWidth(text) - 1;
		int y = bounds.y + bounds.height - 2;

		TextComponent textComponent = new TextComponent();
		textComponent.setPosition(new Point(x, y));
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
}
