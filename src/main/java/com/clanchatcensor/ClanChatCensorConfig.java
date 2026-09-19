package com.clanchatcensor;

import java.awt.Color;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(ClanChatCensorConfig.GROUP)
public interface ClanChatCensorConfig extends Config
{
	String GROUP = "clanchatcensor";

	@ConfigItem(
		keyName = "blockedPlayers",
		name = "Blocked players",
		description = "Players whose messages are censored. Separate names with commas or new lines. Not case sensitive.",
		position = 1
	)
	default String blockedPlayers()
	{
		return "";
	}

	@ConfigItem(
		keyName = "censorText",
		name = "Replacement text",
		description = "What a censored message is replaced with.",
		position = 2
	)
	default String censorText()
	{
		return "Blocked message";
	}

	@ConfigItem(
		keyName = "censorColor",
		name = "Replacement colour",
		description = "Colour of the replacement text.",
		position = 3
	)
	default Color censorColor()
	{
		return new Color(0x9F9F9F);
	}

	@ConfigItem(
		keyName = "clickToHide",
		name = "Click to hide again",
		description = "Clicking a message you have already revealed censors it again.",
		position = 4
	)
	default boolean clickToHide()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clanChat",
		name = "Clan chat",
		description = "Censor messages in your clan chat.",
		position = 5
	)
	default boolean clanChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "guestClanChat",
		name = "Guest clan chat",
		description = "Censor messages in guest clan chat.",
		position = 6
	)
	default boolean guestClanChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "friendsChat",
		name = "Friends chat",
		description = "Censor messages in friends chat.",
		position = 7
	)
	default boolean friendsChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groupIronChat",
		name = "Group ironman chat",
		description = "Censor messages in your group ironman chat.",
		position = 8
	)
	default boolean groupIronChat()
	{
		return true;
	}

	@ConfigItem(
		keyName = "publicChat",
		name = "Public chat",
		description = "Censors public chat.",
		position = 9
	)
	default boolean publicChat()
	{
		return false;
	}
}
