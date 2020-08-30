package net.kjnine.networkleveling;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class ByteMessage {
	
	private Map<String, Object> dataMap;
	
	public ByteMessage(byte[] msg) {
		dataMap = new HashMap<>();
		try (DataInputStream din = new DataInputStream(new ByteArrayInputStream(msg))) {
			while(din.available() > 0) {
				String key = din.readUTF();
				if(key.equals("UUID")) 
					dataMap.put(key, new UUID(din.readLong(), din.readLong()));
				else if(key.equals("ServerTarget") || key.equals("ServerSource") || key.equals("SubChannel"))
					dataMap.put(key, din.readUTF());
				else if(key.equals("Level"))
					dataMap.put(key, din.readInt());
				else if(key.equals("Experience"))
					dataMap.put(key, din.readLong());
				else dataMap.put(key, din.readUTF());
					
			}
		} catch(IOException e) {
			e.printStackTrace();
		}
		
	}
	
	public Map<String, Object> getDatamap() {
		return dataMap;
	}
}
