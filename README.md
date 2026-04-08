## LGTV-groovy

Designed for setups where an LG TV is used as a computer monitor. Unlike regular monitors, TVs don't respond naturally to the computer's power state. This script bridges that gap by automatically turning the TV **on at boot** and **off at shutdown**, and blanking/unblanking the screen when the computer display sleeps or wakes.

Because it runs on groovy, which is a variant of java, it should work on any platform that has java

## Why another?

There are a bunch of alternatives to this: LGTVCompanion, LGBuddy, LGPowerControl, LGTVBtw, LGTVLinuxCompanion but they're all based on Python. Python can be a bit of a pain in the ass, what with installing packages, versions and so forth. This is a groovy script which just requires groovy, and it works with one script.

This script is also easier to use in that it uses the Java preferences system to store client keys for you, so you don't have to screw around with config files, copying encryption keys etc. It "just works".

## Installation

### 1. Install groovy on your system. On arch linux it should be as easy as

```
sudo pacman -S groovy 
```
I developed it with groovy 4, and have tested on groovy 5, so there shouldn't be any versioning issues.

### 2. Prepare the Network
 
To be useful, you probably want your TV to have a fixed IP address.
My preferred way of achieving that is to find my device in my router settings, and assign it an IP address there. This differs by router, but usually it's something like LAN settings → DHCP (or IP Binding), pick your device (if in doubt look up its unique Mac address in your TV settings to figure out which device is which).

In principle you can also assign an IP address in your TV settings, although you could risk a conflict if the router assigns the same address to someone else.

### 3. Prepare the TV

1. **Power on** the TV and connect it to your network.
2. **Enable Wake-on-LAN:**
   * **CX:** Settings → All Settings → Connection → Mobile Connection Management → **TV On with Mobile**
   * **C1–C4:** All Settings → General → Devices → External Devices → **TV On With Mobile** → Turn on via Wi-Fi
   * **C1-C4:** Support → IP Control Settings → Network IP Control → On
   * *Required even when using a wired Ethernet connection.*
3. **Recommended:** Set a static DHCP lease for the TV in your router so its IP doesn't change.

### 4. Run the Script once for each TV you want to control

```
grab the script, either with git clone, or just copying it...
lg.groovy --tvname my-big-living-room-tv --address 192.168.1.54
<The TV will popup and acceptance dialog the first time, and you have about 5 seconds to click ok>
lg.groovy --tvname my-small-bedroom-tv --address 192.168.1.89
```

### 5. Control the TVs manually
You've now registered your TVs and can control them with

```
lg.groovy --tvname my-small-bedroom-tv off
lg.groovy --tvname my-small-bedroom-tv on
```
and if you omit the --tvname argument, it defaults to the last used tv.

### 5. Automate it

To make your TV go on and off with normal Linux power events and screensaver events we need to hook into the acpi system:

```
sudo pacman -S acpid 
sudo systemctl enable acpid
sudo systemctl start acpid
```

Now we need 2 scripts in /etc/acpi/events, monitor_on and monitor_off.
monitor_on:

```
event=jack/videoout VIDEOOUT plug
action=sudo -u <myusername> /my/path/to/lg.groovy on

```
monitor_off:

```
event=jack/videoout VIDEOOUT unplug
action=sudo -u <myusername> /my/path/to/lg.groovy off
```

Normally you need the sudo because you set it up under your user. But if you run the script logged in as root, maybe you can avoid that because the settings will be stored under /root/.java/.userPrefs instead

If you use sudo, you may need to add this to the end of your /etc/sudoers file:

```
<myusername> ALL=NOPASSWD: /my/path/to/lg.groovy

```

## Credits

The strongest similarity to this is https://github.com/cali-95/LGTVLinuxCompanion
If you have any issues, you may want to read that documentation and questions too.

