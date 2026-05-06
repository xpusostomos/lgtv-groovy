#!/bin/groovy
@Grapes([
	@Grab(group='org.java-websocket', module='Java-WebSocket', version='1.5.3'),
	@Grab(group='org.slf4j', module='slf4j-nop', version='2.0.7')
])
package lg

import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.URI
import javax.net.ssl.*
import java.security.cert.X509Certificate
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.CompileStatic
import java.util.prefs.Preferences

@CompileStatic
class LG {
	static String PREFIX = "LG."
	static Preferences prefs = Preferences.userNodeForPackage(LG)

	static void main(String[] args) {
		String tvName = "", address = "", command = ""
		boolean remove = false
		boolean list = false

		int i = 0
		while (i < args.length) {
			switch (args[i]) {
				case "--tvname":  tvName = args[++i]; break
				case "--address": address = args[++i]; break
				case "--remove":  remove = true; break
				case "--list":    list = true; break
				default: command = args[i]
			}
			i++
		}
		if (list) {
            listTvs()
            System.exit(0)
        }
		if (tvName) {
			prefs.put("${PREFIX}lasttv", tvName)
		} else { 
			tvName = prefs.get("${PREFIX}lasttv", null)
		}	
		if (!tvName) {
			System.err.println "Error: You need to specify a name for your personal TV with --tvname"
			System.exit(1)
		}
		if (remove) {
			prefs.remove("${PREFIX}${tvName}.address");
			prefs.remove("${PREFIX}${tvName}.clientkey")
			println "Removed ${tvName}";
			System.exit(0)
		}

		if (address) {
			prefs.put("${PREFIX}${tvName}.address", address)
		} else {
			address = prefs.get("${PREFIX}${tvName}.address", null)
		}

		if (!address) {
			System.err.println "Error: No address for '${tvName}'. Use --address <IP-address>"
			System.exit(1)
		}
		LG lgc = new LG()
		lgc.runWithRetry(address, prefs.get("${PREFIX}${tvName}.clientkey", ""), command, tvName)
	}

	void runWithRetry(String ip, String key, String action, String tvName) {
        String mac = prefs.get("${PREFIX}${tvName}.mac", null)
        
        for (int i = 0; i < 3; i++) {
            println "Attempt ${i + 1} to connect to TV..."
            if (execute(ip, key, action, tvName)) {
                return // Success!
            }
            
            if (mac && action != "off") {
                println "TV not responding, sending WoL and waiting..."
                wake(mac)
                Thread.sleep(5000) // Give it a moment to respond to the packet
            }
        }
        System.err.println "Failed to connect"
        System.exit(1)
    }

	boolean execute(String ip, String key, String action, String tvName) {
		URI uri = new URI("wss://${ip}:3001")
		def client = new WebSocketClient(uri) {
			@Override
			void onOpen(ServerHandshake handshakedata) {
				// The handshake manifest found in the working Python script
				Map<String, Object> manifest = [
					manifestVersion: 1,
					appVersion: "1.1",
					signed: [
						created: "20140509",
						appId: "com.lge.test",
						vendorId: "com.lge",
						localizedAppNames: ["": "LG Remote App"],
						localizedVendorNames: ["": "LG Electronics"],
						permissions: ["CONTROL_POWER", "READ_INSTALLED_APPS", "CONTROL_AUDIO"],
						serial: "2f930e2d2cfe083771f68e4fe7bb07"
					],
					permissions: [
						"LAUNCH", "CONTROL_AUDIO", "CONTROL_POWER", "CONTROL_TV_SCREEN",
						"READ_APP_STATUS", "READ_NETWORK_STATE", "READ_POWER_STATE"
					],
					signatures: [[
						signatureVersion: 1,
						signature: "eyJhbGdvcml0aG0iOiJSU0EtU0hBMjU2Iiwia2V5SWQiOiJ0ZXN0LXNpZ25pbmctY2VydCIsInNpZ25hdHVyZVZlcnNpb24iOjF9.hrVRgjCwXVvE2OOSpDZ58hR+59aFNwYDyjQgKk3auukd7pcegmE2CzPCa0bJ0ZsRAcKkCTJrWo5iDzNhMBWRyaMOv5zWSrthlf7G128qvIlpMT0YNY+n/FaOHE73uLrS/g7swl3/qH/BGFG2Hu4RlL48eb3lLKqTt2xKHdCs6Cd4RMfJPYnzgvI4BNrFUKsjkcu+WD4OO2A27Pq1n50cMchmcaXadJhGrOqH5YmHdOCj5NSHzJYrsW0HPlpuAx/ECMeIZYDh6RMqaFM2DXzdKX9NmmyqzJ3o/0lkk/N97gfVRLW5hA29yeAwaCViZNCP8iC9aO0q9fQojoa7NQnAtw=="
					]]
				] as Map<String, Object>
					Map<String, Object> payload = [
					forcePairing: false,
					pairingType: "PROMPT",
					manifest: manifest
				] as Map<String, Object>
					if (key) {
					payload.put("client-key", key)
				}
				println "sending payload..."
				send(JsonOutput.toJson([
					type: "register", 
					id: "register_42", 
					payload: payload
				]))
			}

			@Override
			void onMessage(String message) {
				Map json = (Map) new JsonSlurper().parseText(message)
				println "received msg: ${message}"
				if (json.type == "registered") {
					Map pl = (Map) json.payload
					String newKey = (String) pl?.get("client-key")
					if (newKey && newKey != key) {
						prefs.put("${PREFIX}${tvName}.clientkey", newKey)
						key = newKey
						println "Key saved for ${tvName}"
						String discoveredMac = getMacFromArp(ip)
						if (discoveredMac) {
							prefs.put("${PREFIX}${tvName}.mac", discoveredMac)
							println "Discovered and saved MAC: ${discoveredMac}"
						}
					}

					if (action) {
						println "Handshake Accepted. Sending ${action}..."
						String uriPath = action == "off" ? "turnOffScreen" : "turnOnScreen"
						send(JsonOutput.toJson([
							id: 42, 
							type: "request", 
							uri: "ssap://com.webos.service.tvpower/power/${uriPath}"
						]))
					}
				}
				if (key) {
					close()
				}
			}
			@Override void onClose(int code, String r, boolean rem) { System.exit(0) }
			@Override void onError(Exception ex) { ex.printStackTrace() }
		}

		// SSL Bypass
		def trustAll = [new X509ExtendedTrustManager() {
			void checkClientTrusted(X509Certificate[] c, String a, Socket s) {}
			void checkServerTrusted(X509Certificate[] c, String a, Socket s) {}
			void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) {}
			void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) {}
			void checkClientTrusted(X509Certificate[] c, String a) {}
			void checkServerTrusted(X509Certificate[] c, String a) {}
			X509Certificate[] getAcceptedIssuers() { return null }
		}] as TrustManager[]
		SSLContext sc = SSLContext.getInstance("TLS")
		sc.init(null, trustAll, new java.security.SecureRandom())
		client.setSocketFactory(sc.getSocketFactory())
		try { 
			return client.connectBlocking(2, java.util.concurrent.TimeUnit.SECONDS);
		} catch (Exception ex) {
			ex.printStackTrace()
			return false
		}
    }
	static void wake(String macAddress) {
        println "Calling wakeonlan for ${macAddress}..."
        try {
            // We use the full path or ensure it's in the environment PATH. 
            // On Arch, it's usually just 'wakeonlan'
            def proc = ["wakeonlan", macAddress].execute()
            proc.waitFor()
            
            if (proc.exitValue() != 0) {
                System.err.println "wakeonlan binary returned error: ${proc.err.text}"
            } else {
                println "wakeonlan executed successfully."
            }
        } catch (IOException e) {
            System.err.println "Failed to execute wakeonlan binary. Is it installed? (sudo pacman -S wakeonlan)"
        }
    }
	

	static String getMacFromArp(String ip) {
		try {
			// We ping once to ensure the TV is in the system's ARP table
			InetAddress.getByName(ip).isReachable(500)
			
			// On Linux/Arch, we can read the ARP table directly
			// Or use the 'arp -an' command
			def process = "arp -an ${ip}".execute()
			def output = process.text
			def matcher = output =~ /([0-9a-fA-F]{2}[:]){5}([0-9a-fA-F]{2})/
			if (matcher.find()) return matcher.group(0)
		} catch (Exception e) {
			return null
		}
		return null
	}
	static void listTvs() {
		println String.format("%-15s | %-15s | %-18s | %s", "Name", "IP Address", "MAC Address", "Has Key")
		println "-" * 65
		
		// Grab all keys and filter for the addresses to identify unique TVs
		String[] keys = prefs.keys()
		keys.findAll { it.startsWith(PREFIX) && it.endsWith(".address") }.each { addrKey ->
			String name = addrKey.replace(PREFIX, "").replace(".address", "")
			String ip = prefs.get(addrKey, "N/A")
			String mac = prefs.get("${PREFIX}${name}.mac", "Unknown")
			String hasKey = prefs.get("${PREFIX}${name}.clientkey", "") ? "Yes" : "No"
			
			println String.format("%-15s | %-15s | %-18s | %s", name, ip, mac, hasKey)
		}
	}
}
