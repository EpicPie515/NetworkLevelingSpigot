package net.kjnine.networkleveling;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.metadata.MetadataValue;
import org.bukkit.plugin.java.JavaPlugin;

public class NetworkLevelingSpigotAdapter extends JavaPlugin {
	
	public static NetworkLevelingSpigotAdapter inst;
	
	public MessagingAdapter netMessaging;
	
	@Override
	public void onEnable() {
		inst = this;
		saveDefaultConfig();
		if(getConfig().getString("netmsg").equalsIgnoreCase("REDIS")) {
			ConfigurationSection sec = getConfig().getConfigurationSection("redis-connection");
			netMessaging = new MessagingAdapter.Redis(getServer(), sec.getString("address"), String.valueOf(sec.getInt("port")), sec.getString("pass"));
		} else {
			netMessaging = new MessagingAdapter.PluginMessaging(getServer());
		}

		netMessaging.registerChannel("NetworkLeveling");
		netMessaging.registerChannel("NLReturn");
		netMessaging.registerChannel("NLMetadata");
		
		netMessaging.addListener("NLMetadata", (data) -> {
			onMessageMetadata(data);
		});
		
		netMessaging.addListener("NLReturn", (data) -> {
			onMessageReturn(data);
		});
		
		LevelAPI.init(this);
	}

	public HashMap<UUID, Consumer<Integer>> levelCallbacks = new HashMap<>();
	public HashMap<UUID, Consumer<Long>> expCallbacks = new HashMap<>();
	public HashMap<UUID, List<NLRequest<?>>> syncRequests = new HashMap<>();
	
	private synchronized void onMessageMetadata(byte[] message) {
		System.out.println("Received PM on Channel [NLMetadata]");
		UUID u = null;
		int lvl = -1;
		long exp = -1;
		Map<String, Object> dataMap = new ByteMessage(message).getDatamap();
		if(dataMap.containsKey("ServerTarget")) {
			if(!dataMap.get("ServerTarget").equals(getServer().getServerName())) {
				System.out.println("PM Addressed to " + dataMap.get("ServerTarget") + ", not us (" + getServer().getServerName() + ")");
				return;
			}
		} else throw new IllegalArgumentException("No ServerTarget in Data");
		if(dataMap.containsKey("UUID")) u = (UUID) dataMap.get("UUID");
		else throw new IllegalArgumentException("No Target Player UUID in Data");
		if(dataMap.containsKey("Level")) lvl = (int) dataMap.get("Level");
		if(dataMap.containsKey("Experience")) exp = (long) dataMap.get("Experience");
		if(lvl < 1) lvl = 1;
		if(exp < 0) exp = 0;
		Player target = getServer().getPlayer(u);
		if(target != null && target.isOnline()) {
			target.setMetadata("nl_level", new FixedMetadataValue(this, lvl));
			target.setMetadata("nl_experience", new FixedMetadataValue(this, exp));
		}
	}
	
	private synchronized void onMessageReturn(byte[] message) {
		UUID uuid = null;
		int lvl = -1;
		long exp = -1;
		Map<String, Object> dataMap = new ByteMessage(message).getDatamap();
		if(dataMap.containsKey("ServerTarget")) {
			if(!dataMap.get("ServerTarget").equals(getServer().getServerName())) {
				System.out.println("PM Addressed to " + dataMap.get("ServerTarget") + ", not us (" + getServer().getServerName() + ")");
				return;
			}
		} else throw new IllegalArgumentException("No ServerTarget in Data");
		if(dataMap.containsKey("UUID")) uuid = (UUID) dataMap.get("UUID");
		else throw new IllegalArgumentException("No Target Player UUID in Data");
		if(dataMap.containsKey("Level")) lvl = (int) dataMap.get("Level");
		if(dataMap.containsKey("Experience")) exp = (long) dataMap.get("Experience");
		String subchannel = (String) dataMap.get("SubChannel");
		if(subchannel == null) throw new IllegalArgumentException("SubChannel not found in Data");
		if(lvl < 1) lvl = 1;
		if(exp < 0) exp = 0;
		if(subchannel.equals("GetLevel")) {
			if(levelCallbacks.containsKey(uuid))
				levelCallbacks.get(uuid).accept(lvl);
			else if(syncRequests.containsKey(uuid)) {
				List<NLRequest<?>> r = syncRequests.get(uuid);
				Iterator<NLRequest<?>> it = r.iterator();
				while(it.hasNext()) {
					NLRequest<?> n = it.next();
					if(n instanceof NLGetLevel) {
						it.remove();
						((NLGetLevel)n).setResult(lvl);
						n.notifyAll();
					}
				}
			}
		} else if(subchannel.equals("GetExperience")) {
			if(expCallbacks.containsKey(uuid))
				expCallbacks.get(uuid).accept(exp);
			else if(syncRequests.containsKey(uuid)) {
				List<NLRequest<?>> r = syncRequests.get(uuid);
				Iterator<NLRequest<?>> it = r.iterator();
				while(it.hasNext()) {
					NLRequest<?> n = it.next();
					if(n instanceof NLGetExperience) {
						it.remove();
						((NLGetExperience)n).setResult(exp);
						n.notifyAll();
					}
				}
			}
		}
	}
	
	private abstract class NLRequest<T extends Number> {
		protected Consumer<T> callback;
		
		private String subchannel;
		
		NLRequest(String subchannel, Consumer<T> callback) {
			this.subchannel = subchannel;
			this.callback = callback;
		}
		
		private void send(Player p) {
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			DataOutputStream dout = new DataOutputStream(bout);
			try {
				dout.writeUTF("SubChannel");
				dout.writeUTF(subchannel);
				dout.writeUTF("UUID");
				dout.writeLong(p.getUniqueId().getMostSignificantBits());
				dout.writeLong(p.getUniqueId().getLeastSignificantBits());
				netMessaging.sendData("NetworkLeveling", bout.toByteArray());
			} catch (IOException e) {
				getLogger().severe("Error sending Request " + subchannel + " for Player " + p.getName() + ":");
				e.printStackTrace();
			}
			addCallback(p.getUniqueId());
		}
		
		private volatile T result;
		
		synchronized T sendSync(Player p) {
			ByteArrayOutputStream bout = new ByteArrayOutputStream();
			DataOutputStream dout = new DataOutputStream(bout);
			try {
				dout.writeUTF("SubChannel");
				dout.writeUTF(subchannel);
				dout.writeUTF("UUID");
				dout.writeLong(p.getUniqueId().getMostSignificantBits());
				dout.writeLong(p.getUniqueId().getLeastSignificantBits());
				netMessaging.sendData("NetworkLeveling", bout.toByteArray());
			} catch (IOException e) {
				getLogger().severe("Error sending Request " + subchannel + " for Player " + p.getName() + ":");
				e.printStackTrace();
			}
			if(!syncRequests.containsKey(p.getUniqueId()))
				syncRequests.put(p.getUniqueId(), new ArrayList<>(Arrays.asList(this)));
			else {
				List<NLRequest<?>> n = syncRequests.get(p.getUniqueId());
				n.add(this);
				syncRequests.put(p.getUniqueId(), n);
			}
			try {
				wait();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
			return result;
		}
		
		void setResult(T result) {
			this.result = result;
		}
		
		protected abstract void addCallback(UUID u);
	}
	
	class NLGetLevel extends NLRequest<Integer> {

		NLGetLevel(Consumer<Integer> callback) {
			super("GetLevel", callback);
		}

		@Override
		protected void addCallback(UUID u) {
			levelCallbacks.put(u, callback);
		}
		
	}
	
	class NLGetExperience extends NLRequest<Long> {

		NLGetExperience(Consumer<Long> callback) {
			super("GetExperience", callback);
		}

		@Override
		protected void addCallback(UUID u) {
			expCallbacks.put(u, callback);
		}
		
	}
	
	void sendNLRequest(Player p, NLRequest<?> req) {
		req.send(p);
	}
	
	@Override
	public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
		if(command.getName().equalsIgnoreCase("netlevelapi")) {
			if(!(sender instanceof Player)) {
				if(args.length == 0) {
					sender.sendMessage("Use /netlevelapi <player> to test from console. Atleast 1 player must be online.");
					return true;
				}
				Player t = getServer().getPlayer(args[0]);
				if(t == null) {
					sender.sendMessage("Use /netlevelapi <player> to test from console. Atleast 1 player must be online.");
					return true;
				}
				sender.sendMessage("Testing NetworkLeveling API-connection with Player " + t.getName() + ":");
				sender.sendMessage("Metadata: ");
				String mdl = "Not Found";
				String mde = "Not Found";
				if(t.hasMetadata("nl_level")) {
					List<MetadataValue> vals = t.getMetadata("nl_level");
					vals = vals.stream().filter(mv -> mv.getOwningPlugin().equals(inst)).collect(Collectors.toList());
					if(vals.size() == 0) mdl = "Found - !! Owning Plugin Invalid !!";
					else mdl = "Found - " + vals.get(0).asInt();
				} if(t.hasMetadata("nl_experience")) {
					List<MetadataValue> vals = t.getMetadata("nl_experience");
					vals = vals.stream().filter(mv -> mv.getOwningPlugin().equals(inst)).collect(Collectors.toList());
					if(vals.size() == 0) mde = "Found - !! Owning Plugin Invalid !!";
					else mde = "Found - " + vals.get(0).asLong();
				}
				sender.sendMessage("-- Level: " + mdl);
				sender.sendMessage("-- Experience: " + mde);
				sender.sendMessage("LevelAPI:");
				sender.sendMessage("-- Sending Callback Requests...");
				long ns = System.nanoTime();
				LevelAPI.getLevel(t, i -> {
					sender.sendMessage("-- LevelAPI#GetLevel: " + i);
					sender.sendMessage("-- GetLevel took " + (System.nanoTime() - ns) + " nanos (" + ((System.nanoTime() - ns)/1000000) +  "ms)");
				});
				LevelAPI.getExperience(t, l -> {
					sender.sendMessage("-- LevelAPI#GetExperience: " + l);
					sender.sendMessage("-- GetExperience took " + (System.nanoTime() - ns) + " nanos (" + ((System.nanoTime() - ns)/1000000) +  "ms)");
				});
				sender.sendMessage("-- Sending SYNC Requests... (In Async Thread to prevent Blocking Main)");
				getServer().getScheduler().runTaskAsynchronously(inst, () -> {
					long nanos = System.nanoTime();
					int lvl = LevelAPI.getLevelSync(t);
					sender.sendMessage("-- LevelAPI#GetLevelSync: " + lvl);
					sender.sendMessage("-- LevelSync took " + (System.nanoTime() - nanos) + " nanos (" + ((System.nanoTime() - nanos)/1000000) +  "ms)");
					nanos = System.nanoTime();
					long exp = LevelAPI.getExperienceSync(t);
					sender.sendMessage("-- LevelAPI#GetExperienceSync: " + exp);
					sender.sendMessage("-- ExperienceSync took " + (System.nanoTime() - nanos) + " nanos (" + ((System.nanoTime() - nanos)/1000000) +  "ms)");
				});
			} else {
				Player p = (Player) sender;
				sender.sendMessage("Testing NetworkLeveling API-connection with Player " + p.getName() + ":");
				sender.sendMessage("Metadata: ");
				String mdl = "Not Found";
				String mde = "Not Found";
				if(p.hasMetadata("nl_level")) {
					List<MetadataValue> vals = p.getMetadata("nl_level");
					vals = vals.stream().filter(mv -> mv.getOwningPlugin().equals(inst)).collect(Collectors.toList());
					if(vals.size() == 0) mdl = "Found - !! Owning Plugin Invalid !!";
					else mdl = "Found - " + vals.get(0).asInt();
				} if(p.hasMetadata("nl_experience")) {
					List<MetadataValue> vals = p.getMetadata("nl_experience");
					vals = vals.stream().filter(mv -> mv.getOwningPlugin().equals(inst)).collect(Collectors.toList());
					if(vals.size() == 0) mde = "Found - !! Owning Plugin Invalid !!";
					else mde = "Found - " + vals.get(0).asLong();
				}
				sender.sendMessage("-- Level: " + mdl);
				sender.sendMessage("-- Experience: " + mde);
				sender.sendMessage("LevelAPI:");
				sender.sendMessage("-- Sending Callback Requests...");
				long ns = System.nanoTime();
				LevelAPI.getLevel(p, i -> {
					sender.sendMessage("-- LevelAPI#GetLevel: " + i);
					sender.sendMessage("-- GetLevel took " + (System.nanoTime() - ns) + " nanos (" + ((System.nanoTime() - ns)/1000000) +  "ms)");
				});
				LevelAPI.getExperience(p, l -> {
					sender.sendMessage("-- LevelAPI#GetExperience: " + l);
					sender.sendMessage("-- GetExperience took " + (System.nanoTime() - ns) + " nanos (" + ((System.nanoTime() - ns)/1000000) +  "ms)");
				});
				sender.sendMessage("-- Sending SYNC Requests... (In Async Thread to prevent Blocking Main)");
				getServer().getScheduler().runTaskAsynchronously(inst, () -> {
					long nanos = System.nanoTime();
					int lvl = LevelAPI.getLevelSync(p);
					sender.sendMessage("-- LevelAPI#GetLevelSync: " + lvl);
					sender.sendMessage("-- LevelSync took " + (System.nanoTime() - nanos) + " nanos (" + ((System.nanoTime() - nanos)/1000000) +  "ms)");
					nanos = System.nanoTime();
					long exp = LevelAPI.getExperienceSync(p);
					sender.sendMessage("-- LevelAPI#GetExperienceSync: " + exp);
					sender.sendMessage("-- ExperienceSync took " + (System.nanoTime() - nanos) + " nanos (" + ((System.nanoTime() - nanos)/1000000) +  "ms)");
				});
			}
			return true;
		}
		return false;
	}
	
}
