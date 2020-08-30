package net.kjnine.networkleveling;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPubSub;

public abstract class MessagingAdapter {
	
	Set<String> channels;
	Map<String, List<Consumer<byte[]>>> listeners;
	
	private AdapterType type;
	protected Server server;
	
	private String[] conn;
	
	public MessagingAdapter(AdapterType adapterType, Server server) {
		type = adapterType;
		this.server = server;
		this.listeners = new HashMap<>();
		this.channels = new HashSet<>();
	}
	
	public AdapterType getAdapterType() {
		return type;
	}
	
	public abstract void registerChannel(String ch);
	
	/**
	 * Not required for pluginmessaging;
	 */
	private void setConnectionSettings(String address, String port, String pass) {
		conn = new String[] {address, port, pass};
	}
	
	public static enum AdapterType {
		REDIS(Redis.class), 
		PLUGINMESSAGING(PluginMessaging.class);
		
		private Class<?> clazz;
		AdapterType(Class<?> clazz) {
			this.clazz = clazz;
		}
		
		public Class<?> getLoaderClass() {
			return clazz;
		}
	}
	
	public abstract void addListener(String channel, Consumer<byte[]> listener);
	
	/**
	 * Sends the data to the specified channel.
	 * @returns whether a successful connection was made and the data was sent.
	 */
	public abstract boolean sendData(String channel, byte[] data);
	
	public static class PluginMessaging extends MessagingAdapter {
		
		
		public PluginMessaging(Server server) {
			super(AdapterType.PLUGINMESSAGING, server);
		}
		
		private class PMListener implements PluginMessageListener {

			@Override
			public void onPluginMessageReceived(String channel, Player player, byte[] message) {
				if(channels.contains(channel)) {
					if(listeners.containsKey(channel)) {
						listeners.get(channel).forEach(c -> c.accept(message));
					}
				}
			}
			
		}

		@Override
		public void registerChannel(String ch) {
			server.getMessenger().registerIncomingPluginChannel(NetworkLevelingSpigotAdapter.inst, ch, new PMListener());
			server.getMessenger().registerOutgoingPluginChannel(NetworkLevelingSpigotAdapter.inst, ch);
			channels.add(ch);
		}

		@Override
		public void addListener(String channel, Consumer<byte[]> listener) {
			if(!channels.contains(channel)) throw new IllegalArgumentException("Channel not registered");
			if(!super.listeners.containsKey(channel)) super.listeners.put(channel, new ArrayList<>());
			List<Consumer<byte[]>> l = super.listeners.get(channel);
			l.add(listener);
			super.listeners.put(channel, l);
		}

		@Override
		public boolean sendData(String channel, byte[] data) {
			if(server.getOnlinePlayers().size() == 0) return false;
			Iterator<? extends Player> it = server.getOnlinePlayers().iterator();
			if(!it.hasNext()) return false;
			try {
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				DataOutputStream dout = new DataOutputStream(bout);
				dout.writeUTF("ServerSource");
				dout.writeUTF(server.getServerName());
				dout.write(data, 0, data.length);
				byte[] fin = bout.toByteArray();
				it.next().sendPluginMessage(NetworkLevelingSpigotAdapter.inst, channel, fin);
			} catch (Exception e) {
				e.printStackTrace();
				return false;
			}
			return true;
		}
		
	}
	
	public static class Redis extends MessagingAdapter implements Closeable {

		private JedisPool pool;
		
		public Redis(Server srv, String address, String port, String pass) {
			super(AdapterType.REDIS, srv);
			super.setConnectionSettings(address, port, pass);
			pool = new JedisPool(address, Integer.parseInt(port));
		}
		
		@Override
		public void registerChannel(String ch) {
			super.channels.add(ch);
			Jedis j = null;
			try {
				j = pool.getResource();
				j.auth(super.conn[2]);
				JedisPubSub jsub = new JedisPubSub() {
					
					@Override
					public void onMessage(String channel, String message) {
						if(channel.equals(ch) && listeners.containsKey(channel)) {
							byte[] data = Base64.getDecoder().decode(message);
							listeners.get(channel).forEach(c -> c.accept(data));
						}
					}
					
				};
				j.subscribe(jsub, ch);
			} finally {
				if(j != null)
					j.close();
			}
		}

		@Override
		public boolean sendData(String channel, byte[] data) {
			Jedis j = null;
			try {
				ByteArrayOutputStream bout = new ByteArrayOutputStream();
				DataOutputStream dout = new DataOutputStream(bout);
				dout.writeUTF("ServerSource");
				dout.writeUTF(server.getServerName());
				dout.write(data, 0, data.length);
				byte[] fin = bout.toByteArray();
				String message = Base64.getEncoder().encodeToString(fin);
				j = pool.getResource();
				j.auth(super.conn[2]);
				j.publish(channel, message);
			} catch (Exception e) {
				e.printStackTrace();
				return false;
			} finally {
				if(j != null)
					j.close();
			}
			return true;
		}

		@Override
		public void addListener(String channel, Consumer<byte[]> listener) {
			if(!channels.contains(channel)) throw new IllegalArgumentException("Channel not registered");
			if(!super.listeners.containsKey(channel)) super.listeners.put(channel, new ArrayList<>());
			List<Consumer<byte[]>> l = super.listeners.get(channel);
			l.add(listener);
			super.listeners.put(channel, l);
		}

		@Override
		public void close() throws IOException {
			pool.close();
		}
		
	}


}
