package net.kjnine.networkleveling;

import java.util.function.Consumer;

import org.bukkit.entity.Player;

import net.kjnine.networkleveling.NetworkLevelingSpigotAdapter.NLGetExperience;
import net.kjnine.networkleveling.NetworkLevelingSpigotAdapter.NLGetLevel;

public class LevelAPI {
	
	private static NetworkLevelingSpigotAdapter pl;
	
	static void init(NetworkLevelingSpigotAdapter plugin) {
		pl = plugin;
	}
	
	public static void getLevel(Player p, Consumer<Integer> callback) {
		pl.sendNLRequest(p, pl.new NLGetLevel(callback));
	}
	
	public static void getExperience(Player p, Consumer<Long> callback) {
		pl.sendNLRequest(p, pl.new NLGetExperience(callback));
	}
	
	/**
	 * Blocks the thread this is run in until it receives a response, use in async threads.
	 */
	public static int getLevelSync(Player p) {
		NLGetLevel nlgl = pl.new NLGetLevel(null);
		return nlgl.sendSync(p);
	}
	
	/**
	 * Blocks the thread this is run in until it receives a response, use in async threads.
	 */
	public static long getExperienceSync(Player p) {
		NLGetExperience nlge = pl.new NLGetExperience(null);
		return nlge.sendSync(p);
	}
	
}
