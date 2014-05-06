## Build and Install

To build and install on device:
./gradlew installDebug

## Use over USB

Can be used in conjuction with adb over to forward ports from a PC to the
outside world.  That is:

```
$ adb forward tcp:2222 tcp:2222
$ ssh localhost -p 2222
```

### Creating a VPN

Building on the pattern, in `/etc/network/interfaces`:

```
iface tun0 inet static
pre-up echo "Setting up android port forwarding"
pre-up adb forward tcp:2222 tcp:2222
pre-up sleep 2
pre-up adb shell am start -a android.intent.action.MAIN -n org.pf/.MainActivity
pre-up sleep 2
pre-up echo "Bringing up tun0"
pre-up ssh -p 2222 -i /root/.ssh/VPN -S /var/run/ssh-vpn-tunnel-control -M -f -w 0:0 127.0.0.1 true
pre-up echo "Server side up"
post-up echo "Client side up"
address 10.0.0.2
pointopoint 10.0.0.1
netmask 255.255.255.0
up route add default gw 10.0.0.1 dev tun0
down route del default gw 10.0.0.1 dev tun0
pre-down ssh -p 2222 -i /root/.ssh/VPN -S /var/run/ssh-vpn-tunnel-control -O exit 127.0.0.1
```

On the server side:

`sudo iptables -t nat -A POSTROUTING -s 10.0.0.0/24 -o eth0 -j MASQUERADE`

```
iface tun0 inet static
	address 10.0.0.1
	netmask 255.255.255.0
	pointopoint 10.0.0.2
```

Full config details at: http://blog.bodhizazen.net/linux/how-to-vpn-using-ssh/

---
App skeleton based on Android `NetworkConnect` sample app.
