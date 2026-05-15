#!/bin/groovy
@Grapes([
	@Grab(group='org.java-websocket', module='Java-WebSocket', version='1.5.3'),
	@Grab(group='org.slf4j', module='slf4j-nop', version='2.0.7')
])
package lg

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
		String input
		boolean usage = false

		int i = 0
		while (i < args.length) {
			switch (args[i]) {
				case "--tvname":  tvName = args[++i]; break
				case "--address": address = args[++i]; break
				case "--input":  input = args[++i]; break
				case "--remove":  remove = true; break
				case "--list":    list = true; break
				case "--help":  usage = true; break;
				default: command = args[i]
			}
			i++
		}
		if (usage) {
			System.out.println("lg --tvname <name> --address <ip> --input <hdmix> --remove --list --help")
			System.exit(0)
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

		if (input) {
			// Standardize to WebOS format: hdmi1 -> HDMI_1
			prefs.put("${PREFIX}${tvName}.input", input)
			println "Default input for ${tvName} set to ${input}"
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
                wake(ip, mac)
                Thread.sleep(5000) // Give it a moment to respond to the packet
            }
        }
        System.err.println "Failed to connect"
        System.exit(1)
    }

	boolean execute(String ip, String key, String action, String tvName) {
		CountDownLatch latch = new CountDownLatch(1)
		boolean[] success = [false]
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
						"READ_APP_STATUS", "READ_NETWORK_STATE", "READ_POWER_STATE", "CONTROL_EXTERNAL_INPUTS"
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
				Map payload = (Map) json.payload
				// 1. Silent ignore for the "Already On" state
				if (payload?.get('errorCode') == "-102") {
					return 
				}
				if (json.id == 43) {
					if (json.type == "response" && payload?.get('returnValue')) {
						println "Successfully switched to ${payload.get('id')}"
					} else if (json.type == "error") {
						System.err.println "HDMI switch failed: ${json.error}"
					}
					close()
					latch.countDown()
					return
				}
				if (json.type == "registered") {
					success[0] = true
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
						String input = prefs.get("${PREFIX}${tvName}.input", null)
						// String formattedInput = input.toUpperCase().replace("HDMI", "HDMI_")
						String formattedInput = input.toLowerCase().replace("hdmi", "com.webos.app.hdmi")
						if (action != "off" && input) {
							Thread.sleep(500)
							println "Auto-switching to saved input: ${formattedInput}"
							send(JsonOutput.toJson([
								id: 43, // Different ID to avoid collision
								type: "request",
								uri: "ssap://system.launcher/launch",
								// uri: "ssap://com.webos.service.tvstaticitem/setExternalInput",
								payload: [id: formattedInput]
							]))
						} else {
							latch.countDown()
						}
					} else { 
						latch.countDown()
					}
				} else {
					println "Unhandled message: ${message}"
					latch.countDown()
				}
			// 	if (key) {
			// 		close()
			// 	}
			}
			@Override void onClose(int code, String r, boolean rem) { latch.countDown() }
			@Override void onError(Exception ex) { ex.printStackTrace(); latch.countDown() }
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
			boolean connected = client.connectBlocking(2, TimeUnit.SECONDS);
			if (!connected) {
				return false
			}
			latch.await(5, TimeUnit.SECONDS)
			return success[0]
		} catch (Exception ex) {
			ex.printStackTrace()
			return false
		}
    }
	static void wake(String ip, String macAddress) {
        println "Calling wakeonlan for ${macAddress}..."
        try {
            // We use the full path or ensure it's in the environment PATH. 
            // On Arch, it's usually just 'wakeonlan'
            def proc = ["wakeonlan", "-i", ip, macAddress].execute()
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
		println String.format("%-15s | %-15s | %-18s | %s | %s", "Name", "IP Address", "MAC Address", "Input", "Has Key")
		println "-" * 65
		
		// Grab all keys and filter for the addresses to identify unique TVs
		String[] keys = prefs.keys()
		keys.findAll { it.startsWith(PREFIX) && it.endsWith(".address") }.each { addrKey ->
			String name = addrKey.replace(PREFIX, "").replace(".address", "")
			String ip = prefs.get(addrKey, "N/A")
			String input = prefs.get("${PREFIX}${name}.input", "None")
			String mac = prefs.get("${PREFIX}${name}.mac", "Unknown")
			String hasKey = prefs.get("${PREFIX}${name}.clientkey", "") ? "Yes" : "No"
			
			println String.format("%-15s | %-15s | %-18s | %-5s | %s", name, ip, mac, input, hasKey)
		}
	}
}
